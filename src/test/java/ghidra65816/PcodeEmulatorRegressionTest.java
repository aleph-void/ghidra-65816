package ghidra65816;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.File;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import ghidra.GhidraApplicationLayout;
import ghidra.framework.Application;
import ghidra.framework.HeadlessGhidraApplicationConfiguration;
import ghidra.pcode.emu.PcodeEmulator;
import ghidra.pcode.emu.PcodeThread;
import ghidra.pcode.exec.PcodeArithmetic;
import ghidra.pcode.exec.PcodeArithmetic.Purpose;
import ghidra.pcode.exec.PcodeExecutorStatePiece.Reason;
import ghidra.pcode.exec.InterruptPcodeExecutionException;
import ghidra.program.model.address.AddressSpace;
import ghidra.program.model.lang.Language;
import ghidra.program.model.lang.LanguageID;
import ghidra.program.model.lang.Register;
import ghidra.program.util.DefaultLanguageService;

/** Small runtime tests for generated 65816 p-code. */
class PcodeEmulatorRegressionTest {
    private static final long CODE_ADDRESS = 0x1000;
    private static final long DATA_ADDRESS = 0x2000;
    private static Language language;

    private record Run(PcodeEmulator emulator, PcodeThread<byte[]> thread, AddressSpace space) {}

    @BeforeAll
    static void initializeGhidra() throws Exception {
        System.err.println("GHIDRA_TEST: entering setup");
        String installDir = System.getenv("GHIDRA_INSTALL_DIR");
        assertNotNull(installDir, "GHIDRA_INSTALL_DIR must be set by the test workflow");

        String externalModules = System.getenv("GHIDRA_EXTERNAL_MODULES");
        assertNotNull(externalModules, "GHIDRA_EXTERNAL_MODULES must point to the compiled language module");
        System.setProperty("ghidra.external.modules", externalModules);

        System.err.println("GHIDRA_TEST: paths validated; initialize application");
        if (!Application.isInitialized()) {
            Application.initializeApplication(
                new GhidraApplicationLayout(new File(installDir)),
                new HeadlessGhidraApplicationConfiguration());
        }
        System.err.println("GHIDRA_TEST: application initialized; load language");
        language = DefaultLanguageService.getLanguageService()
            .getLanguage(new LanguageID("65816:LE:16:default"));
        System.err.println("GHIDRA_TEST: language loaded");
    }

    private static Run start(byte[] code) {
        PcodeEmulator emulator = new PcodeEmulator(language);
        AddressSpace space = language.getDefaultSpace();
        emulator.getSharedState().setVar(space, CODE_ADDRESS, code.length, false, code);
        emulator.addBreakpoint(space.getAddress(CODE_ADDRESS + code.length), "1:1");

        PcodeThread<byte[]> thread = emulator.newThread();
        thread.overrideCounter(space.getAddress(CODE_ADDRESS));
        thread.overrideContextWithDefault();
        return new Run(emulator, thread, space);
    }

    private static void writeRegister(Run run, String name, long value) {
        Register register = language.getRegister(name);
        assertNotNull(register, "Missing 65816 register: " + name);
        PcodeArithmetic<byte[]> arithmetic = run.emulator().getArithmetic();
        run.thread().getState().setVar(
            register, arithmetic.fromConst(value, register.getNumBytes()));
    }

    private static long readRegister(Run run, String name) {
        Register register = language.getRegister(name);
        assertNotNull(register, "Missing 65816 register: " + name);
        PcodeArithmetic<byte[]> arithmetic = run.emulator().getArithmetic();
        return arithmetic.toLong(
            run.thread().getState().getVar(register, Reason.INSPECT), Purpose.INSPECT);
    }

    private static void executeOne(Run run) {
        assertThrows(InterruptPcodeExecutionException.class, () -> run.thread().run(),
            "Execution should stop at the breakpoint immediately after one instruction");
    }

    @Test
    void xbaExchangesAccumulatorBytes() {
        System.err.println("GHIDRA_TEST: starting XBA");
        Run run = start(new byte[] {(byte) 0xeb}); // XBA
        writeRegister(run, "A", 0x12);
        writeRegister(run, "B", 0x34);

        executeOne(run);

        assertEquals(0x34, readRegister(run, "A"));
        assertEquals(0x12, readRegister(run, "B"));
        assertEquals(0, readRegister(run, "NF"));
        assertEquals(0, readRegister(run, "ZF"));
    }

    @Test
    void cmpAbsoluteUsesUnsignedCarryForZeroLessThanFfff() {
        System.err.println("GHIDRA_TEST: starting CMP-less");
        Run run = start(new byte[] {(byte) 0xcd, 0x00, 0x20}); // CMP $2000
        run.emulator().getSharedState().setVar(
            run.space(), DATA_ADDRESS, 2, false, new byte[] {(byte) 0xff, (byte) 0xff});
        writeRegister(run, "C", 0x0000);

        executeOne(run);

        assertEquals(0, readRegister(run, "CF"), "0x0000 is unsigned-less-than 0xffff");
        assertEquals(0, readRegister(run, "ZF"));
    }

    @Test
    void cmpAbsoluteSetsCarryAndZeroForEqualOperands() {
        Run run = start(new byte[] {(byte) 0xcd, 0x00, 0x20}); // CMP $2000
        run.emulator().getSharedState().setVar(
            run.space(), DATA_ADDRESS, 2, false, new byte[] {(byte) 0xff, (byte) 0xff});
        writeRegister(run, "C", 0xffff);

        executeOne(run);

        assertEquals(1, readRegister(run, "CF"));
        assertEquals(1, readRegister(run, "ZF"));
    }
}
