/* MIT License: see LICENSE in this module. */
package ghidra65816;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.data.WordDataType;
import ghidra.program.model.lang.Language;
import ghidra.program.model.lang.Register;
import ghidra.program.model.lang.RegisterValue;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.symbol.SymbolTable;
import ghidra.program.model.util.CodeUnitInsertionException;
import ghidra.util.exception.InvalidInputException;

/**
 * The 65816 reset and interrupt vectors ($00FFE4-$00FFFF, 16-bit pointers into bank 0) as entry
 * points. Each target gets the decode context the CPU has there: emulation mode (E=1, M=X=1) for
 * the emulation vectors including RESET, native mode with 8-bit M and X for the native vectors
 * (the real widths there are those of the interrupted code). Used by the SNES ROM loader and,
 * for other images, by {@link VectorEntryPointAnalyzer}.
 */
public final class VectorEntryPoints {

	/** address, name, emulation mode */
	static final Object[][] VECTORS = {
		{ 0xffe4, "COP_NATIVE", false }, { 0xffe6, "BRK_NATIVE", false },
		{ 0xffe8, "ABORT_NATIVE", false }, { 0xffea, "NMI_NATIVE", false },
		{ 0xffee, "IRQ_NATIVE", false }, { 0xfff4, "COP_EMULATION", true },
		{ 0xfff8, "ABORT_EMULATION", true }, { 0xfffa, "NMI_EMULATION", true },
		{ 0xfffc, "RESET", true }, { 0xfffe, "IRQBRK_EMULATION", true } };

	/** A vector target with the decode context set there. */
	public record Target(Address address, String name, RegisterValue context) {}

	private VectorEntryPoints() {}

	/**
	 * Labels each mapped vector word, and for each one that points into initialised (or
	 * byte-mapped) memory at or above {@code minTarget} labels the target, makes it an entry
	 * point and sets its decode context. A vector of $0000 or $FFFF (unprogrammed) is skipped.
	 */
	public static List<Target> apply(Program program, int minTarget)
			throws MemoryAccessException, InvalidInputException {
		List<Target> out = new ArrayList<>();
		Memory mem = program.getMemory();
		SymbolTable st = program.getSymbolTable();
		Language lang = program.getLanguage();
		AddressSpace bus = lang.getAddressFactory().getDefaultAddressSpace();
		Register ctx = lang.getContextBaseRegister();
		Register mf = lang.getRegister("ctx_MF");
		Register xf = lang.getRegister("ctx_XF");
		Register ef = lang.getRegister("ctx_EF");
		for (Object[] v : VECTORS) {
			Address va = bus.getAddress((Integer) v[0]);
			if (!mem.contains(va, va.add(1))) {
				continue;
			}
			MemoryBlock vb = mem.getBlock(va);
			if (!vb.isInitialized() && !vb.isMapped()) {
				continue;
			}
			try {
				program.getListing().createData(va, WordDataType.dataType);
			}
			catch (CodeUnitInsertionException e) {
				// already defined
			}
			st.createLabel(va, "VEC_" + v[1], SourceType.IMPORTED);
			int target = (mem.getByte(va) & 0xff) | (mem.getByte(va.add(1)) & 0xff) << 8;
			Address t = bus.getAddress(target);
			MemoryBlock tb = mem.getBlock(t);
			if (target < minTarget || target == 0 || target == 0xffff || tb == null ||
				!tb.isInitialized() && !tb.isMapped()) {
				continue;
			}
			st.createLabel(t, (String) v[1], SourceType.IMPORTED);
			st.addExternalEntryPoint(t);
			RegisterValue rv = new RegisterValue(ctx);
			rv = rv.assign(mf, BigInteger.ONE);
			rv = rv.assign(xf, BigInteger.ONE);
			rv = rv.assign(ef, (Boolean) v[2] ? BigInteger.ONE : BigInteger.ZERO);
			try {
				program.getProgramContext().setRegisterValue(t, t, rv);
			}
			catch (ghidra.program.model.listing.ContextChangeException e) {
				// already disassembled there: keep the existing context
			}
			out.add(new Target(t, (String) v[1], rv));
		}
		return out;
	}
}
