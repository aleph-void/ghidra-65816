/* MIT License: see LICENSE in this module. */
package ghidra65816;

import ghidra.app.services.*;
import ghidra.app.util.importer.MessageLog;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.SourceType;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

/**
 * Gives functions that return with RTL the {@code __far} calling convention (JSL/RTL, 3-byte
 * return address). The default {@code __near} convention pops 2 bytes, so after every JSL to a
 * far function constant propagation had the stack pointer one byte off, read the caller's stack
 * slots wrongly and could follow a garbage RTL target into data.
 */
public class FarFunctionAnalyzer extends AbstractAnalyzer {

	private static final String NAME = "65816 Far Functions";

	public FarFunctionAnalyzer() {
		super(NAME, "Sets the __far (JSL/RTL) calling convention on functions whose returns " +
			"are all RTL.", AnalyzerType.FUNCTION_ANALYZER);
		setPriority(AnalysisPriority.FUNCTION_ANALYSIS);
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
		for (Function f : program.getFunctionManager().getFunctions(set, true)) {
			monitor.checkCancelled();
			if (f.getSignatureSource() != SourceType.DEFAULT &&
				f.getSignatureSource() != SourceType.ANALYSIS) {
				continue; // user or imported signature
			}
			int rtl = 0, rts = 0;
			for (Instruction ins : program.getListing().getInstructions(f.getBody(), true)) {
				String mn = ins.getMnemonicString();
				if (mn.equals("RTL")) {
					rtl++;
				}
				else if (mn.equals("RTS")) {
					rts++;
				}
			}
			if (rtl > 0 && rts == 0 && !"__far".equals(f.getCallingConventionName())) {
				try {
					f.setCallingConvention("__far");
				}
				catch (Exception e) {
					log.appendMsg(NAME, f.getEntryPoint() + ": " + e.getMessage());
				}
			}
		}
		return true;
	}
}
