/* MIT License: see LICENSE in this module. */
package ghidra65816;

import java.io.IOException;
import java.util.*;

import ghidra.app.util.MemoryBlockUtils;
import ghidra.app.util.bin.ByteProvider;
import ghidra.app.util.importer.MessageLog;
import ghidra.app.util.opinion.*;
import ghidra.program.database.mem.FileBytes;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.lang.*;
import ghidra.program.model.listing.Program;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.util.exception.CancelledException;

/**
 * Loads a SNES cartridge image (.sfc/.smc, with or without a 512-byte copier header) into the
 * 24-bit 65816 address space.
 * <p>
 * The internal header is looked for at the LoROM ($7FC0), HiROM ($FFC0) and ExHiROM ($40FFC0)
 * positions and scored on the checksum/complement pair, the map-mode byte and the reset vector.
 * The map-mode and chipset bytes then select LoROM, HiROM, ExHiROM, SA-1 (map mode $23) or
 * SuperFX (LoROM with a GSU chipset byte $13-$1A). ROM is mapped where the CPU sees it with
 * byte-mapped mirrors for the other views code commonly runs from (LoROM: bank n at $8000 and
 * FastROM $80+n; HiROM: $C0+n, $40+n and the upper halves at $00+n/$80+n; ExHiROM: the first 4 MB
 * at $C0-$FF, the rest at $40-$7D; SA-1 with its reset MMC state: $C0-$FF linear and LoROM views
 * at $00-$3F/$80-$BF; SuperFX: $40-$5F linear and the LoROM view at $00-$3F/$80-$BF). Cartridge
 * RAM from the header's RAM-size byte is mapped where the board puts it (LoROM $70-$7D:0000,
 * HiROM/ExHiROM $20-$3F:6000 in 8 KB pages, SA-1 BW-RAM at $40 and I-RAM at $3000, SuperFX RAM
 * at $70). Work RAM, the low-RAM mirror and the I/O register pages are created uninitialised. The reset and interrupt
 * vectors become entry points; each target gets the decode context the CPU has there: emulation
 * mode (E=1, M=X=1) for the emulation vectors including RESET, native mode with 8-bit M and X
 * for the native vectors (the real widths there are those of the interrupted code).
 */
public class SnesRomLoader extends AbstractProgramWrapperLoader {

	public static final String NAME = "SNES ROM";
	private static final LanguageCompilerSpecPair LANG =
		new LanguageCompilerSpecPair("65816:LE:16:default", "default");

	private enum Mapping { LOROM, HIROM, EXHIROM, SA1, SUPERFX }

	private record Layout(long skip, Mapping map, int score) {}

	@Override
	public String getName() {
		return NAME;
	}

	private static int u8(byte[] b, int o) {
		return b[o] & 0xff;
	}

	private static int u16(byte[] b, int o) {
		return u8(b, o) | u8(b, o + 1) << 8;
	}

	private static int score(ByteProvider p, long skip, Mapping m) throws IOException {
		long hdr = skip + headerOffset(m);
		if (hdr + 0x40 > p.length()) {
			return -1;
		}
		byte[] h = p.readBytes(hdr, 0x40);
		int s = 0;
		if ((u16(h, 0x1c) ^ u16(h, 0x1e)) == 0xffff) {
			s += 4;
		}
		int mode = u8(h, 0x15) & 0xef;
		int chip = u8(h, 0x16);
		boolean gsu = chip >= 0x13 && chip <= 0x1a;
		if (m == Mapping.LOROM && mode == 0x20 && !gsu || m == Mapping.HIROM && mode == 0x21 ||
			m == Mapping.EXHIROM && mode == 0x25 || m == Mapping.SA1 && mode == 0x23 ||
			m == Mapping.SUPERFX && mode == 0x20 && gsu) {
			s += 2;
		}
		if (u16(h, 0x3c) >= 0x8000) {
			s += 1; // reset vector points into ROM
		}
		int printable = 0;
		for (int i = 0; i < 21; i++) {
			int c = u8(h, i);
			if (c >= 0x20 && c < 0x7f) {
				printable++;
			}
		}
		if (printable == 21) {
			s += 1;
		}
		return s;
	}

	private static long headerOffset(Mapping m) {
		return switch (m) {
			case HIROM -> 0xffc0;
			case EXHIROM -> 0x40ffc0;
			default -> 0x7fc0; // LoROM, SA-1, SuperFX
		};
	}

	private static Layout detect(ByteProvider p) throws IOException {
		long len = p.length();
		long skip = len % 0x8000 == 512 ? 512 : 0;
		if ((len - skip) % 0x8000 != 0 || len - skip < 0x8000) {
			return null;
		}
		Layout best = null;
		for (Mapping m : Mapping.values()) {
			int s = score(p, skip, m);
			if (best == null || s > best.score) {
				best = new Layout(skip, m, s);
			}
		}
		// Require a valid checksum pair or a matching map-mode byte plus a ROM reset vector.
		return best != null && best.score >= 3 ? best : null;
	}

	@Override
	public Collection<LoadSpec> findSupportedLoadSpecs(ByteProvider provider) throws IOException {
		List<LoadSpec> specs = new ArrayList<>();
		if (detect(provider) != null) {
			specs.add(new LoadSpec(this, 0, LANG, true));
		}
		return specs;
	}

	@Override
	protected void load(Program program, ImporterSettings settings)
			throws CancelledException, IOException {
		ByteProvider p = settings.provider();
		Layout lay = detect(p);
		if (lay == null) {
			throw new IOException("not a SNES ROM image");
		}
		AddressSpace bus = program.getAddressFactory().getDefaultAddressSpace();
		Memory mem = program.getMemory();
		FileBytes fb = MemoryBlockUtils.createFileBytes(program, p, settings.monitor());
		long romLen = p.length() - lay.skip;
		var log = settings.log();
		byte[] hdr = p.readBytes(lay.skip + headerOffset(lay.map), 0x40);
		int ramSize = u8(hdr, 0x18) == 0 || u8(hdr, 0x18) > 8 ? 0 : 0x400 << u8(hdr, 0x18);
		try {
			switch (lay.map) {
				case LOROM -> {
					for (long n = 0; n * 0x8000 < romLen; n++) {
						long bank = n < 0x7e ? n : 0x80 + n;
						Address a = bus.getAddress(bank << 16 | 0x8000);
						rom(program, fb, a, lay.skip + n * 0x8000, 0x8000, "LoROM", log);
						if (n < 0x7e) {
							mirror(mem, bus, 0x80 + n << 16 | 0x8000, a, 0x8000, "");
						}
					}
					// LoROM SRAM: $70-$7D:0000-7FFF, 32 KB per bank (mirrored at $F0+)
					for (int i = 0; i * 0x8000 < ramSize && i < 0x0e; i++) {
						MemoryBlock b = ram(mem, bus, (0x70L + i) << 16, Math.min(0x8000,
							ramSize - i * 0x8000), String.format("sram_%02X", 0x70 + i));
						mirrorRam(mem, bus, (0xf0L + i) << 16, b, String.format("sram_%02X", 0xf0 + i));
					}
				}
				case HIROM, EXHIROM -> {
					// ExHiROM: the first 4 MB of the file at $C0-$FF (upper halves also at
					// $80-$BF), the rest at $40-$7D (upper halves at $00-$3D). HiROM: the file at
					// $C0+ with its mirrors at $40+ and the upper halves at $00+/$80+.
					boolean ex = lay.map == Mapping.EXHIROM;
					for (long n = 0; n * 0x10000 < romLen; n++) {
						long size = Math.min(0x10000, romLen - n * 0x10000);
						long bank = ex ? (n < 0x40 ? 0xc0 + n : n) : 0xc0 + n;
						if (ex && n >= 0x40 && n - 0x40 >= 0x3e) {
							break;
						}
						long primary = ex && n >= 0x40 ? 0x40 + (n - 0x40) : bank;
						Address a = bus.getAddress(primary << 16);
						rom(program, fb, a, lay.skip + n * 0x10000, size, ex ? "ExHiROM" : "HiROM",
							log);
						long lo = ex ? (n < 0x40 ? 0x80 + n : n - 0x40) : -1;
						if (!ex && n < 0x3e) {
							mirror(mem, bus, (0x40 + n) << 16, a, size, "");
						}
						if (size > 0x8000) {
							long[] halves = ex ? new long[] { lo } :
								n < 0x40 ? new long[] { n, 0x80 + n } : new long[0];
							for (long b : halves) {
								if (b >= 0 && b < 0xc0 && (b & 0x7f) < 0x40) {
									mirror(mem, bus, b << 16 | 0x8000, a.add(0x8000),
										size - 0x8000, "_hi");
								}
							}
						}
					}
					// HiROM SRAM: $20-$3F:6000-7FFF in 8 KB pages (mirrored at $A0-$BF)
					for (int i = 0; i * 0x2000 < ramSize && i < 0x20; i++) {
						MemoryBlock b = ram(mem, bus, (0x20L + i) << 16 | 0x6000,
							Math.min(0x2000, ramSize - i * 0x2000), String.format("sram_%02X", 0x20 + i));
						mirrorRam(mem, bus, (0xa0L + i) << 16 | 0x6000, b,
							String.format("sram_%02X", 0xa0 + i));
					}
				}
				case SA1, SUPERFX -> {
					// Linear ROM banks with LoROM views of them. SA-1 after reset (MMC CXB..FXB
					// = 0..3): $C0-$FF linear, $00-$1F/$20-$3F/$80-$9F/$A0-$BF:8000 the four
					// 1 MB blocks LoROM style. SuperFX: $40-$5F linear, $00-$3F (and $80-$BF)
					// LoROM style.
					boolean sa1 = lay.map == Mapping.SA1;
					long linear = sa1 ? 0xc0 : 0x40;
					long maxLen = sa1 ? 0x400000 : 0x200000;
					for (long n = 0; n * 0x10000 < Math.min(romLen, maxLen); n++) {
						long size = Math.min(0x10000, romLen - n * 0x10000);
						Address a = bus.getAddress((linear + n) << 16);
						rom(program, fb, a, lay.skip + n * 0x10000, size, sa1 ? "SA-1" : "SuperFX",
							log);
						for (long half = 0; half * 0x8000 < size; half++) {
							long lb = 2 * n + half; // LoROM bank number of this 32 KB
							long view = sa1 ? (lb < 0x40 ? lb : 0x80 + lb - 0x40) : lb;
							if (view >= 0xc0 || !sa1 && view >= 0x40) {
								continue;
							}
							mirror(mem, bus, view << 16 | 0x8000, a.add(half * 0x8000),
								Math.min(0x8000, size - half * 0x8000), "");
							if (!sa1) {
								mirror(mem, bus, (0x80 + view) << 16 | 0x8000,
									a.add(half * 0x8000), Math.min(0x8000, size - half * 0x8000),
									"");
							}
						}
					}
					if (sa1) {
						// BW-RAM at $40-$4F (its first 8 KB also at $00:6000), I-RAM at $3000
						int bw = Math.max(ramSize, 0x2000);
						MemoryBlock b = ram(mem, bus, 0x400000, bw, "bwram");
						mem.createByteMappedBlock("bwram_6000", bus.getAddress(0x6000), b.getStart(),
							0x2000, false).setPermissions(true, true, true);
						ram(mem, bus, 0x3000, 0x800, "iram");
						MemoryBlock r = mem.createUninitializedBlock("io_2200",
							bus.getAddress(0x2200), 0x200, false);
						r.setPermissions(true, true, false);
						r.setVolatile(true);
					}
					else {
						// Game Pak RAM at $70:0000 (header RAM size, 64 KB if unset), GSU
						// registers and cache at $3000-$34FF
						ram(mem, bus, 0x700000, ramSize > 0 ? ramSize : 0x10000, "gsuram");
						MemoryBlock r = mem.createUninitializedBlock("io_3000",
							bus.getAddress(0x3000), 0x500, false);
						r.setPermissions(true, true, false);
						r.setVolatile(true);
					}
				}
			}
			MemoryBlock wram = mem.createUninitializedBlock("wram", bus.getAddress(0x7e0000),
				0x20000, false);
			wram.setPermissions(true, true, true);
			mem.createByteMappedBlock("lowram", bus.getAddress(0), wram.getStart(), 0x2000, false)
					.setPermissions(true, true, true);
			for (long[] io : new long[][] { { 0x2100, 0x100 }, { 0x4000, 0x400 } }) {
				MemoryBlock b = mem.createUninitializedBlock(String.format("io_%04X", io[0]),
					bus.getAddress(io[0]), io[1], false);
				b.setPermissions(true, true, false);
				b.setVolatile(true);
			}
			setUpVectors(program, bus);
		}
		catch (Exception e) {
			throw new IOException("SNES ROM load failed: " + e.getMessage(), e);
		}
	}

	private static void rom(Program program, FileBytes fb, Address a, long off, long size,
			String comment, MessageLog log) throws Exception {
		MemoryBlockUtils.createInitializedBlock(program, false,
			String.format("rom_%02X", a.getOffset() >> 16), a, fb, off, size, comment, null, true,
			false, true, log);
	}

	private static void mirror(Memory mem, AddressSpace bus, long at, Address src, long size,
			String suffix) throws Exception {
		mem.createByteMappedBlock(String.format("rom_%02X%s", at >> 16, suffix),
			bus.getAddress(at), src, size, false).setPermissions(true, false, true);
	}

	private static void mirrorRam(Memory mem, AddressSpace bus, long at, MemoryBlock src,
			String name) throws Exception {
		mem.createByteMappedBlock(name, bus.getAddress(at), src.getStart(), src.getSize(), false)
				.setPermissions(true, true, true);
	}

	private static MemoryBlock ram(Memory mem, AddressSpace bus, long at, long size, String name)
			throws Exception {
		MemoryBlock b = mem.createUninitializedBlock(name, bus.getAddress(at), size, false);
		b.setPermissions(true, true, true);
		return b;
	}

	private void setUpVectors(Program program, AddressSpace bus) throws Exception {
		// SNES vectors point into ROM ($8000-$FFFF of bank 0)
		VectorEntryPoints.apply(program, 0x8000);
	}
}
