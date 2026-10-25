// SNES ROM loader check (test/loader-test.sh): after a headless import, compare the memory map
// with the expectations in checks.txt for this image (made by loader/make_roms.py).
// Script args: <checks.txt> <image name> <outfile>
//@category Testing

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.mem.MemoryBlock;

public class G65816LoaderCheck extends GhidraScript {

	@Override
	protected void run() throws Exception {
		String[] args = getScriptArgs();
		PrintStream out = new PrintStream(args[2]);
		out.println("loader " + currentProgram.getExecutableFormat());
		for (String line : Files.readAllLines(Path.of(args[0]))) {
			String[] p = line.trim().split("\\s+");
			if (p.length < 3 || !p[0].equals(args[1])) {
				continue;
			}
			String res;
			switch (p[1]) {
				case "mem" -> {
					Address a = toAddr(Long.parseLong(p[2], 16));
					byte[] b = new byte[4];
					String got;
					try {
						currentProgram.getMemory().getBytes(a, b);
						got = String.format("%02x%02x%02x%02x", b[0], b[1], b[2], b[3]);
					}
					catch (Exception e) {
						got = "unmapped";
					}
					res = (got.equals(p[3]) ? "PASS " : "FAIL ") + line + " got " + got;
				}
				case "block" -> {
					MemoryBlock blk = currentProgram.getMemory().getBlock(p[2]);
					boolean ok = blk != null &&
						blk.getStart().getOffset() == Long.parseLong(p[3], 16);
					res = (ok ? "PASS " : "FAIL ") + line + " got " + (blk == null ? "none"
							: blk.getStart());
				}
				case "reset" -> {
					Instruction ins = getInstructionAt(toAddr(Long.parseLong(p[2], 16)));
					boolean ok = ins != null && ins.getMnemonicString().equals(p[3]);
					res = (ok ? "PASS " : "FAIL ") + line + " got " + ins;
				}
				default -> res = "FAIL unknown check " + line;
			}
			out.println(res);
		}
		out.close();
	}
}
