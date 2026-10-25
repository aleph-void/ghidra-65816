// Upstream-issue regression check (test/issues-test.sh): flow-disassemble a raw image from its
// reset entry point (E=M=X=1), run auto-analysis, then list every instruction with the number of
// memory references it makes into its own bytes (an immediate operand must make none), and
// decompile the function at the given address.
// Script args: <outfile> <entry-hex> <decompile-hex>
//@category Testing

import java.io.PrintStream;
import java.math.BigInteger;

import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.lang.Register;
import ghidra.program.model.lang.RegisterValue;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.symbol.Reference;

public class G65816IssueCheck extends GhidraScript {

	@Override
	protected void run() throws Exception {
		String[] args = getScriptArgs();
		PrintStream out = new PrintStream(args[0]);
		var lang = currentProgram.getLanguage();
		Register ctxreg = lang.getContextBaseRegister();
		RegisterValue rv = new RegisterValue(ctxreg);
		for (String f : new String[] { "ctx_MF", "ctx_XF", "ctx_EF" }) {
			rv = rv.assign(lang.getRegister(f), BigInteger.ONE);
		}
		Address entry = toAddr(Long.parseLong(args[1], 16));
		DisassembleCommand cmd = new DisassembleCommand(entry, null, true);
		cmd.setInitialContext(rv);
		cmd.applyTo(currentProgram, monitor);
		createFunction(entry, "reset");
		analyzeAll(currentProgram);
		for (Instruction ins : currentProgram.getListing().getInstructions(true)) {
			int self = 0;
			for (Reference r : ins.getReferencesFrom()) {
				Address to = r.getToAddress();
				if (to.isMemoryAddress() && to.compareTo(ins.getMinAddress()) >= 0 &&
					to.compareTo(ins.getMaxAddress()) <= 0) {
					self++;
				}
			}
			out.printf("%06x selfrefs=%d %s%n", ins.getAddress().getOffset(), self, ins);
		}
		Address dec = toAddr(Long.parseLong(args[2], 16));
		Function fn = getFunctionAt(dec);
		if (fn == null) {
			fn = createFunction(dec, null);
		}
		DecompInterface di = new DecompInterface();
		di.openProgram(currentProgram);
		DecompileResults res = di.decompileFunction(fn, 60, monitor);
		out.println("=== decompile " + args[2]);
		out.println(res.decompileCompleted() ? res.getDecompiledFunction().getC()
				: "FAILED " + res.getErrorMessage());
		di.dispose();
		out.close();
	}
}
