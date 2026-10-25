/* MIT License: see LICENSE in this module. */
package ghidra65816;

import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.app.cmd.function.CreateFunctionCmd;
import ghidra.app.services.AbstractAnalyzer;
import ghidra.app.services.AnalysisPriority;
import ghidra.app.services.AnalyzerType;
import ghidra.app.util.importer.MessageLog;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.listing.Program;
import ghidra.util.task.TaskMonitor;

/**
 * Makes the reset and interrupt vectors of a 65816 image that was not imported with the SNES ROM
 * loader (a raw binary, an ELF, ...) into entry points and disassembles them with the decode
 * context the CPU has at each one (see {@link VectorEntryPoints}). It runs when bank 0's vector
 * area ($00FFE4-$00FFFF) is in initialised memory.
 */
public class VectorEntryPointAnalyzer extends AbstractAnalyzer {

	private static final String NAME = "65816 Vector Entry Points";

	public VectorEntryPointAnalyzer() {
		super(NAME, "Creates entry points with the right M/X/E decode context at the 65816 " +
			"reset and interrupt vector targets ($00FFE4-$00FFFF).", AnalyzerType.BYTE_ANALYZER);
		setPriority(AnalysisPriority.BLOCK_ANALYSIS.before());
		setDefaultEnablement(true);
	}

	@Override
	public boolean canAnalyze(Program program) {
		return program.getLanguage().getProcessor().toString().equals("65816") &&
			!SnesRomLoader.NAME.equals(program.getExecutableFormat());
	}

	@Override
	public boolean added(Program program, AddressSetView set, TaskMonitor monitor, MessageLog log)
			throws ghidra.util.exception.CancelledException {
		Address reset = program.getAddressFactory().getDefaultAddressSpace().getAddress(0xfffc);
		if (!set.contains(reset)) {
			return false;
		}
		try {
			for (VectorEntryPoints.Target t : VectorEntryPoints.apply(program, 0)) {
				monitor.checkCancelled();
				if (program.getListing().getInstructionAt(t.address()) != null) {
					continue;
				}
				DisassembleCommand cmd = new DisassembleCommand(t.address(), null, true);
				cmd.setInitialContext(t.context());
				cmd.applyTo(program, monitor);
				if (program.getFunctionManager().getFunctionAt(t.address()) == null) {
					new CreateFunctionCmd(t.address()).applyTo(program, monitor);
				}
			}
		}
		catch (Exception e) {
			log.appendMsg(NAME, "vector entry points: " + e.getMessage());
			return false;
		}
		return true;
	}
}
