/* MIT License: see LICENSE in this module. */
package ghidra65816;

import java.math.BigInteger;
import java.util.*;

import ghidra.app.plugin.core.analysis.ConstantPropagationContextEvaluator;
import ghidra.app.services.*;
import ghidra.app.util.importer.MessageLog;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.*;
import ghidra.program.model.data.DataType;
import ghidra.program.model.symbol.RefType;
import ghidra.program.model.symbol.Reference;
import ghidra.program.util.VarnodeContext;
import ghidra.program.util.SymbolicPropogator;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

/**
 * Tracks the data bank register (DBR) across calls. Absolute operands address DBR:abs, so Ghidra
 * makes a data reference only where constant propagation knows DBR; it starts every function with
 * DBR unknown.
 * <p>
 * Starting from the values set at function entries (RESET gets the reset state DBR=0), each
 * function is propagated and the DBR at each of its direct JSR/JSL sites recorded. A callee whose
 * every caller passes the same value gets it as its entry value, and is propagated again with
 * data references enabled; this repeats until nothing changes. Values a callee sets itself
 * (PHK/PLB, PEA/PLB, ...) are found by the propagation as before. The direct page is not carried:
 * compiled code moves it as a frame pointer, so caller values are stack-relative.
 */
public class BankRegisterAnalyzer extends AbstractAnalyzer {

	private static final String NAME = "65816 DBR Propagation";
	private static final String[] REGS = { "DBR" };

	public BankRegisterAnalyzer() {
		super(NAME, "Carries known data bank (DBR) values from callers into callees so their " +
			"absolute operands get references.",
			AnalyzerType.FUNCTION_ANALYZER);
		setPriority(AnalysisPriority.REFERENCE_ANALYSIS.after().after());
		setDefaultEnablement(true);
	}

	@Override
	public boolean canAnalyze(Program program) {
		String proc = program.getLanguage().getProcessor().toString();
		return proc.equals("65816") || proc.equals("65802");
	}

	@Override
	public boolean added(Program program, AddressSetView set, TaskMonitor monitor, MessageLog log)
			throws CancelledException {
		FunctionManager fm = program.getFunctionManager();
		var ctx = program.getProgramContext();
		Register[] regs = { program.getRegister(REGS[0]) };
		Set<Function> seeded = new HashSet<>();
		boolean changed = true;
		for (int pass = 0; changed && pass < 20; pass++) {
			changed = false;
			// callee entry -> per register: observed value, or null for "conflicting/unknown"
			Map<Address, Long[]> seen = new HashMap<>();
			Map<Address, boolean[]> unknown = new HashMap<>();
			for (Function f : fm.getFunctions(true)) {
				monitor.checkCancelled();
				SymbolicPropogator sp = new SymbolicPropogator(program);
				sp.flowConstants(f.getEntryPoint(), f.getBody(),
					new ConstantPropagationContextEvaluator(monitor, true), false, monitor);
				for (Instruction ins : program.getListing().getInstructions(f.getBody(), true)) {
					if (!ins.getFlowType().isCall()) {
						continue;
					}
					for (Address t : ins.getFlows()) {
						Long[] v = seen.computeIfAbsent(t, k -> new Long[1]);
						boolean[] u = unknown.computeIfAbsent(t, k -> new boolean[1]);
						for (int i = 0; i < regs.length; i++) {
							SymbolicPropogator.Value val = sp.getRegisterValue(ins.getAddress(),
								regs[i]);
							if (val == null || val.isRegisterRelativeValue()) {
								u[i] = true;
							}
							else if (v[i] == null) {
								v[i] = val.getValue();
							}
							else if (v[i] != val.getValue()) {
								u[i] = true;
							}
						}
					}
				}
			}
			for (var e : seen.entrySet()) {
				Function g = fm.getFunctionAt(e.getKey());
				if (g == null) {
					continue;
				}
				// every reference to the callee must be a direct call we saw
				boolean onlyCalls = true;
				for (Reference r : program.getReferenceManager().getReferencesTo(e.getKey())) {
					if (!r.getReferenceType().isCall() &&
						r.getReferenceType().isFlow()) {
						onlyCalls = false;
					}
				}
				if (!onlyCalls || program.getSymbolTable().isExternalEntryPoint(e.getKey())) {
					continue;
				}
				for (int i = 0; i < regs.length; i++) {
					Long v = e.getValue()[i];
					if (v == null || unknown.get(e.getKey())[i]) {
						continue;
					}
					BigInteger cur = ctx.getValue(regs[i], e.getKey(), false);
					if (cur != null && cur.longValue() == v) {
						continue;
					}
					try {
						ctx.setValue(regs[i], e.getKey(), e.getKey(), BigInteger.valueOf(v));
						seeded.add(g);
						changed = true;
					}
					catch (Exception ex) {
						log.appendMsg(NAME, e.getKey() + ": " + ex.getMessage());
					}
				}
			}
		}
		// propagate the seeded functions again, this time creating the data references (only
		// data: a computed flow target found this way would be disassembled without its context)
		ConstantPropagationContextEvaluator dataOnly =
			new ConstantPropagationContextEvaluator(monitor, true) {
				@Override
				public boolean evaluateReference(VarnodeContext context, Instruction instr,
						int pcodeop, Address address, int size, DataType dataType,
						RefType refType) {
					return !refType.isFlow() &&
						super.evaluateReference(context, instr, pcodeop, address, size, dataType,
							refType);
				}
			};
		for (Function f : seeded) {
			monitor.checkCancelled();
			new SymbolicPropogator(program).flowConstants(f.getEntryPoint(), f.getBody(),
				dataOnly, true, monitor);
		}
		return true;
	}
}
