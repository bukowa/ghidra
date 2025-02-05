/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ghidra.app.util.bin.format.dwarf;

import java.io.IOException;
import java.util.*;

import org.apache.commons.io.FilenameUtils;

import ghidra.app.plugin.core.datamgr.util.DataTypeUtils;
import ghidra.app.util.bin.BinaryReader;
import ghidra.app.util.bin.format.dwarf.line.DWARFLine.SourceFileAddr;
import ghidra.app.util.bin.format.dwarf.line.DWARFLineProgramExecutor;
import ghidra.app.util.bin.format.golang.GoConstants;
import ghidra.framework.store.LockException;
import ghidra.program.database.sourcemap.SourceFile;
import ghidra.program.database.sourcemap.SourceFileIdType;
import ghidra.program.model.address.*;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.BookmarkType;
import ghidra.program.model.listing.Program;
import ghidra.program.model.sourcemap.SourceFileManager;
import ghidra.util.*;
import ghidra.util.exception.*;
import ghidra.util.task.TaskMonitor;
import utility.function.Dummy;

/**
 * Performs a DWARF datatype import and a DWARF function import, under the control of the
 * {@link DWARFImportOptions}.
 */
public class DWARFImporter {
	private static final Set<String> SOURCEFILENAMES_IGNORE =
		Set.of(GoConstants.GOLANG_AUTOGENERATED_FILENAME);

	private DWARFProgram prog;
	private DWARFDataTypeManager dwarfDTM;
	private TaskMonitor monitor;
	private static final int MAX_NUM_SOURCE_LINE_ERROR_REPORTS = 200;
	private static final int MAX_NUM_SOURCE_LINE_WARNING_REPORTS = 200;
	private int numSourceLineErrorReports = 0;
	private int numSourceLineWarningReports = 0;

	// TODO: consider making this an analyzer option
	public static final String DEFAULT_COMPILATION_DIR = "DWARF_DEFAULT_COMP_DIR";

	public DWARFImporter(DWARFProgram prog, TaskMonitor monitor) {
		this.prog = prog;
		this.monitor = monitor;
		this.dwarfDTM = prog.getDwarfDTM();
	}

	/**
	 * Moves previously imported DataTypes from the /DWARF/_UNCATEGORIZED_ folder into
	 * folder /DWARF/source_code_filename.ext/...
	 * <p>
	 * When moving / renaming DataTypes, you only need to worry about named DataTypes.
	 * Pointers and Arrays, which can only exist by referring to a named DataType, get
	 * moved / renamed automagically by the DataTypeManager.
	 * <p>
	 * After moving each DataType, if the folder is empty, remove the folder.
	 *
	 * @throws CancelledException
	 */
	private void moveTypesIntoSourceFolders() throws CancelledException {

		// Sort by category to reduce the amount of thrashing the DTM does reloading
		// categories.
		List<DataTypePath> importedTypes = dwarfDTM.getImportedTypes();
		Collections.sort(importedTypes,
			(dtp1, dtp2) -> dtp1.getCategoryPath()
					.getPath()
					.compareTo(dtp2.getCategoryPath().getPath()));

		monitor.setIndeterminate(false);
		monitor.setShowProgressValue(true);
		monitor.initialize(importedTypes.size());
		monitor.setMessage("DWARF Move Types");

		CategoryPath unCatRootCp = prog.getUncategorizedRootDNI().getOrganizationalCategoryPath();
		CategoryPath rootCP = prog.getRootDNI().asCategoryPath();

		for (DataTypePath dataTypePath : importedTypes) {
			monitor.checkCancelled();
			monitor.incrementProgress(1);

			if ((monitor.getProgress() % 5) == 0) {
				/* balance between getting work done and pampering the swing thread */
				Swing.runNow(Dummy.runnable());
			}

			DataType dataType =
				prog.getGhidraProgram().getDataTypeManager().getDataType(dataTypePath);
			if (dataType != null && !(dataType instanceof Pointer || dataType instanceof Array)) {
				DWARFSourceInfo dsi = dwarfDTM.getSourceInfo(dataType);
				if (dsi != null && dsi.filename() != null) {
					CategoryPath dataTypeOrigCP = dataType.getCategoryPath();
					CategoryPath newRoot = new CategoryPath(rootCP, dsi.filename());
					CategoryPath newCP =
						rehomeCategoryPathSubTree(unCatRootCp, newRoot, dataTypeOrigCP);
					if (newCP != null) {
						try {
							dataType.setCategoryPath(newCP);
							if (dataType instanceof Composite) {
								fixupAnonStructMembers((Composite) dataType, dataTypeOrigCP, newCP);
							}
							deleteEmptyCategoryPaths(dataTypeOrigCP);
						}
						catch (DuplicateNameException e) {
							// if some unexpected error occurs during a move operation,
							// the datatype is left in its original location under _UNCATEGORIZED_.
							Msg.error(this,
								"Failed to move " + dataType.getDataTypePath() + " to " + newCP);
						}
					}
				}
			}
		}

		monitor.setMessage("DWARF Move Types - Done");
	}

	/*
	 * Moves DataTypes found in a Composite's fields, if they appear to be anonymous
	 * and don't have their own source code location information.
	 */
	private void fixupAnonStructMembers(Composite compositeDataType, CategoryPath origCategoryPath,
			CategoryPath newCP) throws DuplicateNameException {
		CategoryPath origCompositeNSCP =
			new CategoryPath(origCategoryPath, compositeDataType.getName());
		CategoryPath destCompositeNSCP = new CategoryPath(newCP, compositeDataType.getName());
		for (DataTypeComponent component : compositeDataType.getDefinedComponents()) {
			DataType dtcDT = component.getDataType();
			if (dtcDT instanceof Array || dtcDT instanceof Pointer) {
				dtcDT = DataTypeUtils.getNamedBaseDataType(dtcDT);
			}
			if (dtcDT.getCategoryPath().equals(origCompositeNSCP) &&
				dwarfDTM.getSourceInfo(dtcDT) == null) {
				dtcDT.setCategoryPath(destCompositeNSCP);
			}
		}
		deleteEmptyCategoryPaths(origCompositeNSCP);
	}

	private void deleteEmptyCategoryPaths(CategoryPath cp) {
		DataTypeManager dtm = prog.getGhidraProgram().getDataTypeManager();
		while (!CategoryPath.ROOT.equals(cp)) {
			Category cat = dtm.getCategory(cp);
			Category parentCat = dtm.getCategory(cp.getParent());
			if (cat == null || parentCat == null) {
				break;
			}

			if (cat.getDataTypes().length != 0 || cat.getCategories().length != 0) {
				break;
			}

			if (!parentCat.removeEmptyCategory(cat.getName(), monitor)) {
				Msg.error(this, "Failed to delete empty category " + cp);
				break;
			}
			cp = parentCat.getCategoryPath();
		}
	}

	private CategoryPath rehomeCategoryPathSubTree(CategoryPath origRoot, CategoryPath newRoot,
			CategoryPath cp) {
		if (origRoot.equals(cp)) {
			return newRoot;
		}
		List<String> origRootParts = origRoot.asList();
		List<String> cpParts = cp.asList();
		if (cpParts.size() < origRootParts.size() ||
			!origRootParts.equals(cpParts.subList(0, origRootParts.size()))) {
			return null;
		}
		return new CategoryPath(newRoot, cpParts.subList(origRootParts.size(), cpParts.size()));
	}

	/**
	 * Reads the dwarf source line info and applies it via the program's {@link SourceFileManager}.
	 * Note that source file paths which are relative after normalization will have all leading
	 * "." and "/../" entries stripped and then be placed under artificial directories based on
	 * {@code DEFAULT_COMPILATION_DIR}.
	 * 
	 * @param reader reader
	 * @throws CancelledException if cancelled by user
	 * @throws IOException if error during reading
	 * @throws LockException if invoked without exclusive access
	 */
	private void addSourceLineInfo(BinaryReader reader)
			throws CancelledException, IOException, LockException {
		if (reader == null) {
			Msg.warn(this, "Can't add source line info - reader is null");
			return;
		}
		int entryCount = 0;
		Program ghidraProgram = prog.getGhidraProgram();
		long maxLength = prog.getImportOptions().getMaxSourceMapEntryLength();
		List<DWARFCompilationUnit> compUnits = prog.getCompilationUnits();
		monitor.initialize(compUnits.size(), "DWARF: Reading Source Map Info");
		SourceFileManager sourceManager = ghidraProgram.getSourceFileManager();
		List<SourceFileAddr> sourceInfo = new ArrayList<>();
		for (DWARFCompilationUnit cu : compUnits) {
			monitor.increment();
			sourceInfo.addAll(cu.getLine().getAllSourceFileAddrInfo(cu, reader));
		}
		monitor.setIndeterminate(true);
		monitor.setMessage("Sorting " + sourceInfo.size() + " entries");
		sourceInfo.sort((i, j) -> Long.compareUnsigned(i.address(), j.address()));
		monitor.setIndeterminate(false);
		monitor.initialize(sourceInfo.size(), "DWARF: Applying Source Map Info");
		Map<SourceFileAddr, SourceFile> sfasToSourceFiles = new HashMap<>();
		Set<SourceFileAddr> badSfas = new HashSet<>();
		AddressSet warnedAddresses = new AddressSet();

		for (int i = 0; i < sourceInfo.size() - 1; i++) {
			monitor.increment(1);
			SourceFileAddr sfa = sourceInfo.get(i);
			if (SOURCEFILENAMES_IGNORE.contains(sfa.fileName()) ||
				SOURCEFILENAMES_IGNORE.contains(FilenameUtils.getName(sfa.fileName())) ||
				sfa.isEndSequence()) {
				continue;
			}
			if (sfa.fileName() == null) {
				continue;
			}
			if (badSfas.contains(sfa)) {
				continue;
			}

			Address addr = prog.getCodeAddress(sfa.address());

			if (warnedAddresses.contains(addr)) {
				continue; // only warn once per address
			}
			if (!ghidraProgram.getMemory().getExecuteSet().contains(addr)) {
				String warningString =
					"entry for non-executable address; skipping: file %s line %d address: %s %x"
							.formatted(sfa.fileName(), sfa.lineNum(), addr.toString(),
								sfa.address());

				if (numSourceLineWarningReports++ < MAX_NUM_SOURCE_LINE_WARNING_REPORTS) {
					prog.logWarningAt(addr, addr.toString(), warningString);
				}
				warnedAddresses.add(addr);
				continue;
			}

			long length = getLength(i, sourceInfo);
			if (length < 0) {
				length = 0;
				String warningString =
					"Error calculating entry length for file %s line %d address %s %x; replacing " +
						"with length 0 entry".formatted(sfa.fileName(), sfa.lineNum(),
							addr.toString(), sfa.address());
				if (numSourceLineWarningReports++ < MAX_NUM_SOURCE_LINE_WARNING_REPORTS) {
					prog.logWarningAt(addr, addr.toString(), warningString);
				}
			}
			if (length > maxLength) {
				String warningString = ("entry for file %s line %d address: %s %x length %d too" +
					" large, replacing with length 0 entry").formatted(sfa.fileName(),
						sfa.lineNum(), addr.toString(), sfa.address(), length);
				length = 0;
				if (numSourceLineWarningReports++ < MAX_NUM_SOURCE_LINE_WARNING_REPORTS) {
					prog.logWarningAt(addr, addr.toString(), warningString);
				}
			}

			SourceFile source = sfasToSourceFiles.get(sfa);
			if (source == null) {
				String path = SourceFileUtils.fixDwarfRelativePath(sfa.fileName(),
					DEFAULT_COMPILATION_DIR);
				try {
					SourceFileIdType type =
						sfa.md5() == null ? SourceFileIdType.NONE : SourceFileIdType.MD5;
					source = new SourceFile(path, type, sfa.md5());
					sourceManager.addSourceFile(source);
					sfasToSourceFiles.put(sfa, source);
				}
				catch (IllegalArgumentException e) {
					String errorString = "Exception creating source file: " + e.getMessage();
					if (numSourceLineErrorReports++ < MAX_NUM_SOURCE_LINE_ERROR_REPORTS) {
						reportError(errorString, addr);
					}
					badSfas.add(sfa);
					continue;
				}
			}

			try {
				sourceManager.addSourceMapEntry(source, sfa.lineNum(), addr, length);
			}
			catch (AddressOverflowException | IllegalArgumentException e) {
				String errorString = e.getClass().getName() + " for source map entry %s %d %s %x %d"
						.formatted(source.getFilename(), sfa.lineNum(), addr.toString(),
							sfa.address(), length);
				if (numSourceLineErrorReports++ < MAX_NUM_SOURCE_LINE_ERROR_REPORTS) {
					reportError(errorString, addr);
				}
				continue;
			}
			entryCount++;
		}
		if (numSourceLineWarningReports >= MAX_NUM_SOURCE_LINE_WARNING_REPORTS) {
			Msg.warn(this, "Additional warnings suppressed (%d total warnings)"
					.formatted(numSourceLineWarningReports));
		}
		if (numSourceLineErrorReports >= MAX_NUM_SOURCE_LINE_ERROR_REPORTS) {
			Msg.error(this, "Additional errors suppressed (%d total errors)"
					.formatted(numSourceLineErrorReports));
		}
		Msg.info(this, "Added %d source map entries".formatted(entryCount));
	}

	private void reportError(String errorString, Address addr) {
		if (prog.getImportOptions().isUseBookmarks()) {
			prog.getGhidraProgram()
					.getBookmarkManager()
					.setBookmark(addr, BookmarkType.ERROR, DWARFProgram.DWARF_BOOKMARK_CAT,
						errorString);
		}
		else {
			Msg.error(this, errorString);
		}
	}

	/**
	 * In the DWARF format, source line info is only recorded for an address x
	 * if the info for x differs from the info for address x-1.
	 * To calculate the length of a source map entry, we need to look for the next
	 * SourceFileAddr with a different address (there can be multiple records per address
	 * so there's no guarantee that the SourceFileAddr at position i+1 has a different 
	 * address). Special end-of-sequence markers are used to mark the end of a function,
	 * so if we find one of these we stop searching.  These markers have their addresses tweaked 
	 * by one, which we undo (see {@link DWARFLineProgramExecutor#executeExtended}).
	 * @param i starting index
	 * @param allSFA sorted list of SourceFileAddr
	 * @return computed length or -1 on error
	 */
	private long getLength(int i, List<SourceFileAddr> allSFA) {
		SourceFileAddr iAddr = allSFA.get(i);
		long iOffset = iAddr.address();
		for (int j = i + 1; j < allSFA.size(); j++) {
			SourceFileAddr current = allSFA.get(j);
			long currentAddr = current.address();
			if (current.isEndSequence()) {
				return currentAddr + 1 - iOffset;
			}
			if (currentAddr != iOffset) {
				return currentAddr - iOffset;
			}
		}
		return -1;
	}

	/**
	 * Imports DWARF information according to the {@link DWARFImportOptions} set.
	 * @return
	 * @throws IOException
	 * @throws DWARFException
	 * @throws CancelledException
	 */
	public DWARFImportSummary performImport()
			throws IOException, DWARFException, CancelledException {
		monitor.setIndeterminate(false);
		monitor.setShowProgressValue(true);

		DWARFImportOptions importOptions = prog.getImportOptions();
		DWARFImportSummary importSummary = prog.getImportSummary();

		long start_ts = System.currentTimeMillis();
		if (importOptions.isImportDataTypes()) {
			dwarfDTM.importAllDataTypes(monitor);
			prog.getGhidraProgram().flushEvents();
			importSummary.dataTypeElapsedMS = System.currentTimeMillis() - start_ts;
		}

		if (importOptions.isImportFuncs()) {
			long funcstart_ts = System.currentTimeMillis();
			DWARFFunctionImporter dfi = new DWARFFunctionImporter(prog, monitor);
			dfi.importFunctions();
			importSummary.funcsElapsedMS = System.currentTimeMillis() - funcstart_ts;
		}

		if (importOptions.isOrganizeTypesBySourceFile()) {
			moveTypesIntoSourceFolders();
		}

		if (importOptions.isOutputSourceLineInfo()) {
			if (!prog.getGhidraProgram().hasExclusiveAccess()) {
				Msg.showError(this, null, "Unable to add source map info",
					"Exclusive access to the program is required to add source map info");
			}
			else {
				try {
					addSourceLineInfo(prog.getDebugLineBR());
				}
				catch (LockException e) {
					throw new AssertException("LockException after exclusive access verified");
				}
			}
		}

		importSummary.totalElapsedMS = System.currentTimeMillis() - start_ts;

		return importSummary;
	}
}
