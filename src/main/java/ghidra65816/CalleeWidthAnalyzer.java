/* MIT License: see LICENSE in this module. */
package ghidra65816;

import java.math.BigInteger;
import java.util.*;

import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.app.services.*;
import ghidra.app.util.importer.MessageLog;
import ghidra.program.model.address.*;
import ghidra.program.model.lang.Register;
import ghidra.program.model.lang.RegisterValue;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

/**
 * Carries a callee's REP/SEP width change back to its callers. SLEIGH flow context gives a callee
 * the caller's M/X widths, but after JSR/JSL the caller continues with its own: a helper such as
 * "sep #$20; rts" left the code after each call to it decoded with the wrong operand widths.
 * <p>
 * For each function whose every RTS/RTL has the same M (or X) width, different from the width at
 * its entry (so the callee sets it on every path, rather than passing the caller's through), the
 * code after each call to it is cleared and disassembled again with that width. Functions that
 * restore the caller's widths (PHP ... PLP) return their entry widths and are left alone.
 */
public class CalleeWidthAnalyzer extends AbstractAnalyzer {

	private static final String NAME = "65816 Callee Width Changes";

	public CalleeWidthAnalyzer() {
		super(NAME, "Re-disassembles the code after a JSR/JSL with the M/X widths the callee " +
			"returns with, when the callee sets them (REP/SEP) on every return path.",
			AnalyzerType.FUNCTION_ANALYZER);
		setPriority(AnalysisPriority.FUNCTION_ANALYSIS.after());
		setDefaultEnablement(true);
	}

	@Override
	public boolean canAnalyze(Program program) {
		String proc = program.getLanguage().getProcessor().toString();
		return proc.equals("65816") || proc.equals("65802");
	}

	/** Width a callee returns with that differs from its entry width, or -1. */
	private static int exitWidth(Program program, Function f, Register r) {
		Listing listing = program.getListing();
		Instruction first = listing.getInstructionAt(f.getEntryPoint());
		if (first == null) {
			return -1;
		}
		BigInteger entry = first.getValue(r, false);
		BigInteger exit = null;
		for (Instruction ins : listing.getInstructions(f.getBody(), true)) {
			String mn = ins.getMnemonicString();
			if (!mn.equals("RTS") && !mn.equals("RTL")) {
				continue;
			}
			BigInteger v = ins.getValue(r, false);
			if (v == null || exit != null && !exit.equals(v)) {
				return -1;
			}
			exit = v;
		}
		if (exit == null || entry == null || exit.equals(entry)) {
			return -1;
		}
		return exit.intValue();
	}

	@Override
	public boolean added(Program program, AddressSetView set, TaskMonitor monitor, MessageLog log)
			throws CancelledException {
		Register ctx = program.getLanguage().getContextBaseRegister();
		Register mf = program.getLanguage().getRegister("ctx_MF");
		Register xf = program.getLanguage().getRegister("ctx_XF");
		Listing listing = program.getListing();
		ReferenceManager refs = program.getReferenceManager();
		Deque<Function> work = new ArrayDeque<>();
		for (Function f : program.getFunctionManager().getFunctions(set, true)) {
			work.add(f);
		}
		Set<Address> done = new HashSet<>();
		int budget = 10000;
		while (!work.isEmpty() && budget-- > 0) {
			monitor.checkCancelled();
			Function f = work.poll();
			int m = exitWidth(program, f, mf);
			int x = exitWidth(program, f, xf);
			if (m < 0 && x < 0) {
				continue;
			}
			for (Reference ref : refs.getReferencesTo(f.getEntryPoint())) {
				if (!ref.getReferenceType().isCall()) {
					continue;
				}
				Instruction call = listing.getInstructionAt(ref.getFromAddress());
				if (call == null || call.getFallThrough() == null) {
					continue;
				}
				Address ft = call.getFallThrough();
				RegisterValue want = call.getRegisterValue(ctx);
				if (m >= 0) {
					want = want.assign(mf, BigInteger.valueOf(m));
				}
				if (x >= 0) {
					want = want.assign(xf, BigInteger.valueOf(x));
				}
				Instruction next = listing.getInstructionAt(ft);
				if (next != null && (m < 0 || next.getValue(mf, false).intValue() == m) &&
					(x < 0 || next.getValue(xf, false).intValue() == x)) {
					continue;
				}
				if (!done.add(ft)) {
					continue; // two callees disagree about this call site: leave it
				}
				Function caller = program.getFunctionManager().getFunctionContaining(ft);
				clearFlowFrom(program, ft, call.getMaxAddress().next(), monitor);
				try {
					program.getProgramContext().setRegisterValue(ft, ft, want);
				}
				catch (Exception e) {
					log.appendMsg(NAME, ft + ": " + e.getMessage());
				}
				DisassembleCommand cmd = new DisassembleCommand(ft, null, true);
				cmd.setInitialContext(want);
				cmd.applyTo(program, monitor);
				if (caller != null) {
					work.add(caller);
				}
			}
		}
		return true;
	}

	/**
	 * Clears the instructions reached by fall-through and branches from {@code start} that were
	 * decoded in the caller's (now wrong) context: instructions at or after {@code min} with the
	 * same context as the first one, stopping at returns, function entry points and code with
	 * another context.
	 */
	private static void clearFlowFrom(Program program, Address start, Address min,
			TaskMonitor monitor) {
		Listing listing = program.getListing();
		Instruction first = listing.getInstructionAt(start);
		if (first == null) {
			return;
		}
		RegisterValue bad = first.getRegisterValue(program.getLanguage().getContextBaseRegister());
		AddressSet clear = new AddressSet();
		Deque<Address> todo = new ArrayDeque<>(List.of(start));
		while (!todo.isEmpty() && clear.getNumAddresses() < 0x10000) {
			Address a = todo.poll();
			Instruction ins = listing.getInstructionAt(a);
			if (ins == null || clear.contains(a) || a.compareTo(min) < 0 ||
				program.getFunctionManager().getFunctionAt(a) != null && !a.equals(start) ||
				!bad.equals(ins.getRegisterValue(bad.getRegister()))) {
				continue;
			}
			clear.add(ins.getMinAddress(), ins.getMaxAddress());
			if (ins.getFallThrough() != null) {
				todo.add(ins.getFallThrough());
			}
			if (ins.getFlowType().isJump()) {
				todo.addAll(Arrays.asList(ins.getFlows()));
			}
		}
		// only the code units go: functions, labels and references to the addresses stay
		for (AddressRange r : clear) {
			listing.clearCodeUnits(r.getMinAddress(), r.getMaxAddress(), false);
		}
	}
}
