/* MIT License: see LICENSE in this module. */
package ghidra65816;

import java.util.*;

import ghidra.app.services.*;
import ghidra.app.util.importer.MessageLog;
import ghidra.program.model.address.*;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.*;
import ghidra.program.model.listing.BookmarkType;
import ghidra.util.exception.CancelledException;
import ghidra.util.task.TaskMonitor;

/**
 * Removes code that Ghidra disassembled at the target of a speculative data reference ("Found code
 * from operand reference") or of a pointer held in data, when nothing gave that code a decode
 * context.
 * <p>
 * Every 65816 operand length depends on M/X/E, so code decoded without a context (unset M/X/E,
 * read as 16-bit native) is guesswork; with immediates now visible as constants, constant
 * propagation offers many more speculative pointers into ROM data. Such code is cleared and the
 * bookmark kept as "65816: not disassembled (no decode context)". Code the user disassembles, and
 * code reached by flow from a vector, entry point or call, always has a context and is kept.
 */
public class ContextlessCodeAnalyzer extends AbstractAnalyzer {

	private static final String NAME = "65816 Contextless Code Cleanup";

	public ContextlessCodeAnalyzer() {
		super(NAME, "Clears code disassembled from speculative operand references when it has " +
			"no M/X/E decode context.", AnalyzerType.INSTRUCTION_ANALYZER);
		setPriority(AnalysisPriority.REFERENCE_ANALYSIS.after().after().after());
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
		Register mf = program.getLanguage().getRegister("ctx_MF");
		Listing listing = program.getListing();
		var bm = program.getBookmarkManager();
		List<Bookmark> found = new ArrayList<>();
		bm.getBookmarksIterator(set.getMinAddress(), true).forEachRemaining(b -> {
			if (set.contains(b.getAddress()) && "Found Code".equals(b.getCategory())) {
				found.add(b);
			}
		});
		// code started at a pointer that some data holds (Reference analyzer, pointer data)
		Set<Address> starts = new LinkedHashSet<>();
		for (Bookmark b : found) {
			starts.add(b.getAddress());
		}
		for (Instruction ins : listing.getInstructions(set, true)) {
			if (ins.getValue(mf, false) != null) {
				continue;
			}
			boolean fromData = false, other = false;
			for (var r : program.getReferenceManager().getReferencesTo(ins.getAddress())) {
				if (!r.getReferenceType().isFlow() &&
					listing.getInstructionContaining(r.getFromAddress()) == null &&
					listing.getDefinedDataContaining(r.getFromAddress()) != null) {
					fromData = true;
				}
				else if (r.getReferenceType().isFlow() &&
					listing.getInstructionContaining(r.getFromAddress()) != null &&
					listing.getInstructionContaining(r.getFromAddress()).getValue(mf,
						false) == null) {
					// a branch from the same context-less code: not a start
				}
				else {
					other = true;
				}
			}
			if (fromData && !other) {
				starts.add(ins.getAddress());
			}
		}
		for (Address start : starts) {
			monitor.checkCancelled();
			Bookmark b = bm.getBookmark(start, BookmarkType.ANALYSIS, "Found Code");
			Instruction first = listing.getInstructionAt(start);
			if (first == null || first.getValue(mf, false) != null ||
				program.getFunctionManager().getFunctionAt(start) != null &&
					program.getReferenceManager().getReferenceCountTo(start) == 0) {
				continue;
			}
			AddressSet clear = new AddressSet();
			Deque<Address> todo = new ArrayDeque<>(List.of(start));
			while (!todo.isEmpty()) {
				Instruction ins = listing.getInstructionAt(todo.poll());
				if (ins == null || clear.contains(ins.getAddress()) ||
					ins.getValue(mf, false) != null) {
					continue;
				}
				clear.add(ins.getMinAddress(), ins.getMaxAddress());
				if (ins.getFallThrough() != null) {
					todo.add(ins.getFallThrough());
				}
				for (Address t : ins.getFlows()) {
					todo.add(t);
				}
			}
			Function f = program.getFunctionManager().getFunctionAt(start);
			if (f != null) {
				program.getFunctionManager().removeFunction(start);
			}
			for (AddressRange r : clear) {
				listing.clearCodeUnits(r.getMinAddress(), r.getMaxAddress(), false);
			}
			// and the disassembly errors that code left behind
			if (!clear.isEmpty()) {
				Address end = clear.getMaxAddress();
				try {
					end = end.addNoWrap(4);
				}
				catch (Exception e) {
					// keep the last address
				}
				bm.removeBookmarks(new AddressSet(clear.getMinAddress(), end), BookmarkType.ERROR,
					monitor);
			}
			if (b != null) {
				bm.removeBookmark(b);
			}
			bm.setBookmark(start, BookmarkType.ANALYSIS, "65816",
				"not disassembled: code at a data pointer with no M/X/E decode context");
		}
		return true;
	}
}
