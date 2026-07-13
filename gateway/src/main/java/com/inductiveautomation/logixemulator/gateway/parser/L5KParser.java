package com.inductiveautomation.logixemulator.gateway.parser;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.inductiveautomation.logixemulator.gateway.parser.DataTypeUtils.getDefaultValue;
import static com.inductiveautomation.logixemulator.gateway.parser.DataTypeUtils.normalizeDataType;

/**
 * Parser for Rockwell L5K files (text-based Studio 5000 / RSLogix 5000 export format).
 *
 * <p><b>Rewritten per {@code docs/plans/L5K-GRAMMAR.md} (v10.1.0, Phase 2).</b> The v10.0.0
 * parser matched tag declarations with whole-file regexes and a handful of boolean flags
 * ({@code inControllerScope}/{@code inTagBlock}); it had no concept of an {@code AOI
 * PARAMETERS}/{@code LOCAL_TAGS}/{@code ROUTINE} boundary, so ladder rung text
 * ({@code N: XIC(Sim)OTL(Sts);}) was lexically indistinguishable from a tag declaration
 * ({@code Name : Type (...) := val;}) and got parsed as a bogus tag named {@code N} with
 * {@code data_type} set to the first ladder mnemonic encountered (L5K-GRAMMAR.md §0, the root
 * cause of the real-file corruption this rewrite fixes).
 *
 * <p>This parser is <b>statement-oriented and block-stack-bounded</b> (L5K-GRAMMAR.md §1.4/§1.5):
 * <ol>
 *   <li>An explicit block stack tracks every keyword-delimited begin/end block (CONTROLLER, DATATYPE,
 *       MODULE, ADD_ON_INSTRUCTION_DEFINITION, PARAMETERS, LOCAL_TAGS, TAG, PROGRAM, ROUTINE, ...)
 *       by its own {@code END_*} keyword, never by indentation.</li>
 *   <li>A line may start a tag/member declaration <b>only</b> when the block stack's top-of-stack
 *       resolves to one of the five whitelisted contexts: a TAG block whose parent is CONTROLLER
 *       or PROGRAM, a PARAMETERS/LOCAL_TAGS block whose parent is an AOI definition, or a DATATYPE
 *       block (§1.4's normative whitelist). Every other context - including every ROUTINE/
 *       ST_ROUTINE/FBD_ROUTINE body - is opaque and its lines are skipped outright, so rung text
 *       can never reach the tag parser.</li>
 *   <li>Inside a whitelisted context, one logical statement is accumulated across as many physical
 *       lines as needed until an unquoted, unbracketed {@code ;} terminates it (§1.4 R3/R4) - the
 *       fix for multi-line UDT/AOI/string initialisers that the old line-oriented parser
 *       mis-split.</li>
 *   <li>A {@link #RUNG_TYPE_NAMES rung-shaped name} or {@link #LADDER_MNEMONICS ladder mnemonic
 *       type} reaching a whitelisted context anyway (the block stack having somehow failed to
 *       exclude it) hard-fails loudly (§5.1 tripwire) instead of silently emitting a pseudo-tag.</li>
 * </ol>
 *
 * <p>Output is the same vendor-neutral parsed-tag JSON model {@link L5XParser} produces
 * ({@code global_tags}/{@code programs[].tags}/{@code udt_members}/{@code read_only}/...) - zero
 * changes are required to {@code AddressSpaceBuilder} or the {@code address} package.
 */
public class L5KParser implements PLCParser {

    private static final Logger logger = LoggerFactory.getLogger(L5KParser.class);

    /** Rung-type tokens a ladder rung line begins with (L5K-GRAMMAR.md §2.8, manual §4). */
    private static final Set<String> RUNG_TYPE_NAMES = Set.of(
        "N", "I", "D", "IR", "rR", "R", "rI", "rN", "e", "er");

    /**
     * Ladder/FBD instruction mnemonics that must never become a tag's data type (§5.1).
     *
     * <p>Deliberate deviation from the §5.1 example list: {@code MESSAGE} is NOT in this set,
     * because {@code MESSAGE} is also a legitimate predefined structured type (§2.1 - real
     * exports declare {@code SomeTag : MESSAGE (...)}) and treating it as a mnemonic would
     * hard-fail every valid file that uses MSG instructions' backing tags. The ladder mnemonic
     * for a message instruction is {@code MSG}, which IS in the set.
     */
    private static final Set<String> LADDER_MNEMONICS = Set.of(
        "XIC", "XIO", "OTE", "OTL", "OTU", "MOV", "MOVE", "COP", "CPS", "TON", "TOF", "RTO",
        "CTU", "CTD", "JSR", "MSG", "ADD", "SUB", "MUL", "DIV", "EQU", "EQ", "NEQ",
        "GEQ", "LEQ", "GRT", "LES", "LIM", "ONS", "OSR", "OSF", "CLR", "LBL", "NOP");

    /** Atomic scalar types that never expand to {@code udt_members} (§2.1). STRING is excluded -
     *  it is registered in {@link RockwellBuiltInTypes} and expands to LEN/DATA (ADDRESSING §3.10). */
    private static final Set<String> ATOMIC_TYPES = Set.of(
        "BOOL", "BOOLEAN", "SINT", "USINT", "INT", "UINT", "DINT", "UDINT", "LINT", "ULINT",
        "REAL", "LREAL", "FLOAT", "BYTE", "WORD", "DWORD", "LWORD",
        "DT", "LDT", "LTIME", "TIME");

    // -----------------------------------------------------------------------------------------
    // Block keyword table (L5K-GRAMMAR.md §1.2). Keys are the begin keyword (upper-case); values
    // the matching END_* keyword. Every recognised keyword pushes/pops regardless of whether its
    // content is ever inspected (R2) - this is what keeps ROUTINE/MODULE/CONFIG/etc. bodies out of
    // the tag parser without needing to understand their internals.
    // -----------------------------------------------------------------------------------------
    private static final Map<String, String> BLOCK_KEYWORDS = new LinkedHashMap<>();

    static {
        BLOCK_KEYWORDS.put("CONTROLLER", "END_CONTROLLER");
        BLOCK_KEYWORDS.put("DATATYPE", "END_DATATYPE");
        BLOCK_KEYWORDS.put("MODULE", "END_MODULE");
        BLOCK_KEYWORDS.put("CONNECTION", "END_CONNECTION");
        BLOCK_KEYWORDS.put("ADD_ON_INSTRUCTION_DEFINITION", "END_ADD_ON_INSTRUCTION_DEFINITION");
        BLOCK_KEYWORDS.put("PARAMETERS", "END_PARAMETERS");
        BLOCK_KEYWORDS.put("LOCAL_TAGS", "END_LOCAL_TAGS");
        BLOCK_KEYWORDS.put("TAG", "END_TAG");
        BLOCK_KEYWORDS.put("PROGRAM", "END_PROGRAM");
        BLOCK_KEYWORDS.put("CHILD_PROGRAMS", "END_CHILD_PROGRAMS");
        BLOCK_KEYWORDS.put("TASK", "END_TASK");
        BLOCK_KEYWORDS.put("CONFIG", "END_CONFIG");
        BLOCK_KEYWORDS.put("ROUTINE", "END_ROUTINE");
        BLOCK_KEYWORDS.put("ST_ROUTINE", "END_ST_ROUTINE");
        BLOCK_KEYWORDS.put("FBD_ROUTINE", "END_FBD_ROUTINE");
        BLOCK_KEYWORDS.put("SHEET", "END_SHEET");
        BLOCK_KEYWORDS.put("SFC_ROUTINE", "END_SFC_ROUTINE");
        // Manual-defined blocks not present in either dissected real file (L5K-GRAMMAR.md §1.2
        // rule 4) - recognised defensively so a future export does not corrupt the block stack.
        BLOCK_KEYWORDS.put("TREND", "END_TREND");
        BLOCK_KEYWORDS.put("QUICK_WATCH_LIST", "END_QUICK_WATCH_LIST");
        BLOCK_KEYWORDS.put("WATCH_TAG", "END_WATCH_TAG");
        BLOCK_KEYWORDS.put("ENCODED_DATA", "END_ENCODED_DATA");
        BLOCK_KEYWORDS.put("DEPENDENCIES", "END_DEPENDENCIES");
    }

    /** The exact END_* keywords that close a recognised block; anything else starting with
     *  {@code END_} (e.g. a parameter named {@code END_OF_CYCLE}) is ordinary content. */
    private static final Set<String> KNOWN_END_KEYWORDS = Set.copyOf(BLOCK_KEYWORDS.values());

    @Override
    public JsonObject parse(String filePath) {
        try {
            String content = Files.readString(Paths.get(filePath));
            return parseContent(content, filePath);
        } catch (java.io.IOException e) {
            logger.error("[L5K Parser] Failed to read file: {} - {}", filePath, e.getMessage(), e);
            return null;
        } catch (Exception e) {
            logger.error("[L5K Parser] Error processing file: {} - {}", filePath, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public JsonObject parseContent(String fileContent, String fileName) {
        try {
            logger.info("Parsing L5K file: {} ({} chars)", fileName, fileContent.length());

            String[] lines = fileContent.split("\\r?\\n", -1);
            L5KFileParse parse = new L5KFileParse(fileName);
            parse.run(lines);

            JsonObject result = parse.toJson();

            int totalTags = parse.controllerTags.size()
                + parse.programs.values().stream().mapToInt(p -> p.tags.size()).sum();
            int userTypeCount = parse.udtDefs.size() + parse.aoiDefs.size();

            // DoD FIX-6 (11/07/2026, carried forward): a file with zero tag instances AND zero
            // user-defined types/AOIs has nothing recognisable in it at all.
            if (totalTags == 0 && userTypeCount == 0) {
                logger.error(
                    "L5K parse failed for '{}': no recognisable TAG/PROGRAM sections or "
                        + "user-defined types found across {} line(s) - not a valid L5K export",
                    fileName, lines.length);
                return null;
            }

            logger.info(
                "L5K parsing complete for '{}': {} controller tags, {} program(s), {} UDTs, "
                    + "{} AOIs, {} alias tags, {} array tags, structurallyClean={}",
                fileName, parse.controllerTags.size(), parse.programs.size(), parse.udtDefs.size(),
                parse.aoiDefs.size(), parse.counters.aliasTagCount, parse.counters.arrayTagCount,
                parse.counters.structurallyClean());

            return result;

        } catch (L5KStructuralException e) {
            logger.error("L5K parse failed for '{}': {}", fileName, e.getMessage());
            return null;
        } catch (Exception e) {
            // An exception mid-parse is a genuine failure, not a demo opportunity - the specific
            // cause is logged in full; the caller surfaces an honest 4xx naming file + parser.
            logger.error("L5K parse failed for '{}': {} - {}",
                fileName, e.getClass().getSimpleName(), e.getMessage(), e);
            return null;
        }
    }

    @Override
    public boolean canHandle(String fileName) {
        return fileName != null && fileName.toLowerCase().endsWith(".l5k");
    }

    @Override
    public String getParserType() {
        return "l5k";
    }

    // ===========================================================================================
    // Block stack
    // ===========================================================================================

    /** One open block on the parser's block stack (L5K-GRAMMAR.md §1.4). */
    private static final class Frame {
        final String keyword;
        final String name;
        /** Mutable member-accumulator for DATATYPE/PARAMETERS/LOCAL_TAGS frames; a program's own
         *  tags array for PROGRAM frames. Left {@code null} for frames with no payload. */
        final JsonArray payload;

        Frame(String keyword, String name, JsonArray payload) {
            this.keyword = keyword;
            this.name = name;
            this.payload = payload;
        }
    }

    /** The five whitelisted tag-bearing contexts (L5K-GRAMMAR.md §1.4) plus NONE (opaque). */
    private enum Ctx { CTRL_TAG, PROG_TAG, AOI_PARAM, AOI_LOCAL, DATATYPE, NONE }

    /** Thrown for every §5.1 HARD FAIL condition; caught once in {@link #parseContent}. */
    private static final class L5KStructuralException extends RuntimeException {
        L5KStructuralException(String message) {
            super(message);
        }
    }

    /** One controller/program tag statement whose type-dependent expansion is deferred until the
     *  whole file has been scanned, so a type referenced before its DATATYPE/AOI definition is
     *  still resolved correctly (L5K-GRAMMAR.md §1.4 does not require definitions to precede use,
     *  even though both real files happen to order them that way). */
    private record DeferredStatement(String body, Ctx ctx, String programName, int lineNo) { }

    /** Per-file, per-construct skip/warn accounting surfaced in the parse summary (§5.2/§5.3).
     *  Tag/program/type-definition counts are derived from the built model itself. */
    private static final class Counters {
        int aliasTagCount;
        int arrayTagCount;
        int skippedTagLines;
        int unknownTypeTags;
        int unmodelledFbdTypes;
        int droppedNoneAccess;
        boolean tripwireFired;

        boolean structurallyClean() {
            return !tripwireFired && skippedTagLines == 0;
        }
    }

    /** Holds the full parse state for one file - block stack, type registry, deferred statements,
     *  and the resulting tag trees. Package-private-visible only within this class. */
    private static final class L5KFileParse {
        final String fileName;
        final Deque<Frame> stack = new ArrayDeque<>();
        final Map<String, JsonObject> typeDefs = new LinkedHashMap<>();
        final Set<String> udtDefs = new LinkedHashSet<>();
        final Set<String> aoiDefs = new LinkedHashSet<>();
        final List<DeferredStatement> deferredControllerTags = new ArrayList<>();
        final Map<String, List<DeferredStatement>> deferredProgramTags = new LinkedHashMap<>();
        final Map<String, ProgramBuild> programs = new LinkedHashMap<>();
        final JsonArray controllerTags = new JsonArray();
        final Counters counters = new Counters();
        String controllerName;

        L5KFileParse(String fileName) {
            this.fileName = fileName;
            typeDefs.putAll(BuiltInTypeConverter.convert());
        }

        void run(String[] lines) {
            int i = 0;
            // Cross-line quote state for OPAQUE content only (§1.4 R4 applied to skipping): a
            // rung comment `RC: "..."` may span physical lines, and a quoted continuation line
            // that happens to begin with an upper-case token (e.g. `TAG`) must not be mistaken
            // for a block keyword. Whitelisted contexts do their own quote tracking inside
            // accumulateStatement, always starting from a clean state.
            boolean opaqueInDouble = false;
            boolean opaqueInSingle = false;
            boolean opaqueInComment = false;

            while (i < lines.length) {
                String raw = lines[i];

                if (opaqueInDouble || opaqueInSingle || opaqueInComment) {
                    boolean[] state = scanQuoteState(raw, opaqueInDouble, opaqueInSingle, opaqueInComment);
                    opaqueInDouble = state[0];
                    opaqueInSingle = state[1];
                    opaqueInComment = state[2];
                    i++;
                    continue;
                }

                String trimmed = raw.strip();
                if (trimmed.isEmpty()) {
                    i++;
                    continue;
                }

                // An END line is recognised ONLY when the line's first token exactly equals a
                // known END_* keyword: real AOI parameters are named things like END_OF_CYCLE
                // (file A line 8843), and a prefix match would misread that declaration as a
                // block terminator, desyncing the statement scanner.
                String firstToken = firstToken(trimmed);
                if (KNOWN_END_KEYWORDS.contains(firstToken.toUpperCase(java.util.Locale.ROOT))) {
                    popFor(firstToken.toUpperCase(java.util.Locale.ROOT), i + 1);
                    i++;
                    continue;
                }

                String beginKeyword = matchBeginKeyword(trimmed);
                if (beginKeyword != null) {
                    // §1.2 rule 3: a begin line's attribute list may wrap across many physical
                    // lines, closed by the matching ')'. Consume the whole header up front so
                    // wrapped attribute lines are never misread as member/tag statements.
                    int headerEnd = consumeHeader(lines, i);

                    // Self-closing check spans the WHOLE header: real exports emit multi-line
                    // CONFIG blocks whose `END_CONFIG` sits after the closing ')' on the LAST
                    // header line (file A lines 81195-81199), not on the begin line as the
                    // grammar doc's "single-line" note suggests. Quoted spans are stripped
                    // first so a description containing "END_..." text cannot false-positive.
                    StringBuilder headerText = new StringBuilder();
                    for (int h = i; h <= headerEnd; h++) {
                        headerText.append(lines[h]).append(' ');
                    }
                    String endKeyword = BLOCK_KEYWORDS.get(beginKeyword);
                    boolean selfClosing = containsWord(stripQuoted(headerText.toString()), endKeyword);
                    if (!selfClosing) {
                        pushFor(beginKeyword, trimmed);
                    }
                    i = headerEnd + 1;
                    continue;
                }

                Ctx ctx = currentContext();
                if (ctx == Ctx.NONE) {
                    // Opaque content (ROUTINE/MODULE/CONFIG/... bodies) - skipped outright per
                    // §1.4 R1: the whitelist, not this line's shape, decides. Quote/comment
                    // state is carried across lines so a multi-line quoted rung comment cannot
                    // fake a block keyword on a continuation line.
                    boolean[] state = scanQuoteState(raw, false, false, false);
                    opaqueInDouble = state[0];
                    opaqueInSingle = state[1];
                    opaqueInComment = state[2];
                    i++;
                    continue;
                }

                StatementScan scan = accumulateStatement(lines, i);
                if (scan.residue() != null) {
                    // FIX-C (§1.4 R3's own scope note: real files are one-declaration-per-line,
                    // so this is latent, but the whole point of the statement-oriented rewrite is
                    // to never silently drop content). Content following a same-line ';' is not
                    // itself parsed as a further statement - simpler and acceptable per the fix
                    // spec - but it must be LOUD, never silent: count it and WARN.
                    counters.skippedTagLines++;
                    logger.warn(
                        "L5K '{}' line {}: residue '{}' after a same-line statement terminator is "
                            + "not parsed as its own statement - counted, not silently dropped "
                            + "(skippedTagLines={})",
                        fileName, i + 1, truncateForLog(scan.residue()), counters.skippedTagLines);
                }
                processStatement(scan.text, ctx, i + 1);
                i = scan.nextLine;
            }

            if (!stack.isEmpty()) {
                Frame unclosed = stack.peek();
                throw new L5KStructuralException(
                    "Unbalanced block nesting: '" + unclosed.keyword + "' opened but never closed "
                        + "with '" + BLOCK_KEYWORDS.get(unclosed.keyword) + "' before EOF");
            }
            if (controllerName == null) {
                throw new L5KStructuralException("No CONTROLLER block found - not an L5K export");
            }

            resolveDeferredStatements();
        }

        private void pushFor(String beginKeyword, String trimmedLine) {
            String name = null;
            if (beginKeyword.equals("CONTROLLER") || beginKeyword.equals("DATATYPE")
                || beginKeyword.equals("PROGRAM") || beginKeyword.equals("MODULE")
                || beginKeyword.equals("TASK") || beginKeyword.equals("ADD_ON_INSTRUCTION_DEFINITION")
                || beginKeyword.equals("ROUTINE") || beginKeyword.equals("ST_ROUTINE")
                || beginKeyword.equals("FBD_ROUTINE") || beginKeyword.equals("SFC_ROUTINE")) {
                name = extractName(trimmedLine, beginKeyword);
            }

            JsonArray payload = null;
            if (beginKeyword.equals("DATATYPE")) {
                payload = new JsonArray();
                if (name != null) {
                    JsonObject def = new JsonObject();
                    def.addProperty("name", name);
                    def.add("members", payload);
                    typeDefs.put(name, def);
                    udtDefs.add(name);
                }
            } else if (beginKeyword.equals("PARAMETERS") || beginKeyword.equals("LOCAL_TAGS")) {
                payload = new JsonArray();
            } else if (beginKeyword.equals("PROGRAM") && name != null) {
                ProgramBuild pb = new ProgramBuild(name);
                programs.put(name, pb);
                payload = pb.tags;
            }

            if (beginKeyword.equals("CONTROLLER") && controllerName == null) {
                controllerName = name;
            }
            if (beginKeyword.equals("ADD_ON_INSTRUCTION_DEFINITION") && name != null) {
                JsonObject def = new JsonObject();
                def.addProperty("name", name);
                def.add("members", new JsonArray());
                typeDefs.put(name, def);
                aoiDefs.add(name);
            }

            stack.push(new Frame(beginKeyword, name, payload));
        }

        /** @param endKeyword a member of {@link #KNOWN_END_KEYWORDS}, upper-case (e.g.
         *      {@code "END_TAG"}) - the caller has already filtered out END_-prefixed
         *      identifiers that are not block terminators. */
        private void popFor(String endKeyword, int lineNo) {
            String expectedFor = null;
            for (Map.Entry<String, String> e : BLOCK_KEYWORDS.entrySet()) {
                if (e.getValue().equals(endKeyword)) {
                    expectedFor = e.getKey();
                    break;
                }
            }
            if (stack.isEmpty() || !stack.peek().keyword.equals(expectedFor)) {
                throw new L5KStructuralException(
                    "line " + lineNo + ": '" + endKeyword + "' does not match the innermost open "
                        + "block (" + (stack.isEmpty() ? "none open" : stack.peek().keyword) + ")");
            }

            Frame closed = stack.pop();
            if (closed.keyword.equals("PARAMETERS") || closed.keyword.equals("LOCAL_TAGS")) {
                // Fold this AOI sub-block's members into the AOI definition now that it is
                // complete (the definition itself lives further down the stack).
                Frame aoiFrame = findAncestor("ADD_ON_INSTRUCTION_DEFINITION");
                if (aoiFrame != null && aoiFrame.name != null) {
                    JsonObject aoiDef = typeDefs.get(aoiFrame.name);
                    if (aoiDef != null) {
                        aoiDef.getAsJsonArray("members").addAll(closed.payload);
                    }
                }
            }
        }

        private Frame findAncestor(String keyword) {
            for (Frame f : stack) {
                if (f.keyword.equals(keyword)) {
                    return f;
                }
            }
            return null;
        }

        private Ctx currentContext() {
            if (stack.isEmpty()) {
                return Ctx.NONE;
            }
            Frame top = stack.peek();
            switch (top.keyword) {
                case "DATATYPE":
                    return Ctx.DATATYPE;
                case "TAG": {
                    Frame parent = parentOf(top);
                    if (parent != null && parent.keyword.equals("CONTROLLER")) {
                        return Ctx.CTRL_TAG;
                    }
                    if (parent != null && parent.keyword.equals("PROGRAM")) {
                        return Ctx.PROG_TAG;
                    }
                    return Ctx.NONE;
                }
                case "PARAMETERS": {
                    Frame parent = parentOf(top);
                    return (parent != null && parent.keyword.equals("ADD_ON_INSTRUCTION_DEFINITION"))
                        ? Ctx.AOI_PARAM : Ctx.NONE;
                }
                case "LOCAL_TAGS": {
                    Frame parent = parentOf(top);
                    return (parent != null && parent.keyword.equals("ADD_ON_INSTRUCTION_DEFINITION"))
                        ? Ctx.AOI_LOCAL : Ctx.NONE;
                }
                default:
                    return Ctx.NONE;
            }
        }

        private Frame parentOf(Frame top) {
            Frame parent = null;
            boolean seenTop = false;
            for (Frame f : stack) {
                if (!seenTop) {
                    if (f == top) {
                        seenTop = true;
                    }
                    continue;
                }
                return f;
            }
            return parent;
        }

        private String programNameOf() {
            Frame parent = parentOf(stack.peek());
            return parent != null ? parent.name : null;
        }

        private void processStatement(String rawStatement, Ctx ctx, int lineNo) {
            String body = stripTerminator(rawStatement);
            if (body.isEmpty()) {
                return;
            }

            if (ctx == Ctx.DATATYPE) {
                DatatypeMemberParser.parse(body, stack.peek().payload, counters, fileName, lineNo);
                return;
            }

            // The four NAME : TYPE-shaped contexts (§2.1/§2.2/§2.7). Defer expansion; only
            // shape-detect + tripwire-check now (cheap, needs no type registry).
            TagLineShape shape = TagLineShape.classify(body);
            if (shape == null) {
                RungTripwire.check(body, fileName, lineNo, counters);
                return;
            }

            if (ctx == Ctx.CTRL_TAG) {
                deferredControllerTags.add(new DeferredStatement(body, ctx, null, lineNo));
            } else if (ctx == Ctx.PROG_TAG) {
                String progName = programNameOf();
                deferredProgramTags.computeIfAbsent(progName, k -> new ArrayList<>())
                    .add(new DeferredStatement(body, ctx, progName, lineNo));
            } else if (ctx == Ctx.AOI_PARAM || ctx == Ctx.AOI_LOCAL) {
                JsonObject member = TagStatementBuilder.buildAoiMember(
                    shape, ctx == Ctx.AOI_PARAM, counters, fileName, lineNo);
                if (member != null) {
                    stack.peek().payload.add(member);
                }
            }
        }

        private void resolveDeferredStatements() {
            for (DeferredStatement d : deferredControllerTags) {
                JsonObject tag = TagStatementBuilder.buildTag(
                    TagLineShape.classify(d.body()), typeDefs, counters, fileName, d.lineNo());
                if (tag != null) {
                    controllerTags.add(tag);
                }
            }
            for (Map.Entry<String, List<DeferredStatement>> e : deferredProgramTags.entrySet()) {
                ProgramBuild pb = programs.computeIfAbsent(e.getKey(), ProgramBuild::new);
                for (DeferredStatement d : e.getValue()) {
                    JsonObject tag = TagStatementBuilder.buildTag(
                        TagLineShape.classify(d.body()), typeDefs, counters, fileName, d.lineNo());
                    if (tag != null) {
                        pb.tags.add(tag);
                    }
                }
            }
        }

        JsonObject toJson() {
            JsonObject result = new JsonObject();
            result.addProperty("vendor", "rockwell");
            result.addProperty("format", "L5K");
            if (controllerName != null) {
                result.addProperty("controller", controllerName);
            }

            if (controllerTags.size() > 0) {
                result.add("global_tags", controllerTags);
            }

            if (!programs.isEmpty()) {
                JsonArray progArray = new JsonArray();
                for (ProgramBuild pb : programs.values()) {
                    JsonObject p = new JsonObject();
                    p.addProperty("name", pb.name);
                    p.add("tags", pb.tags);
                    progArray.add(p);
                }
                result.add("programs", progArray);
            }

            if (!udtDefs.isEmpty()) {
                JsonArray udts = new JsonArray();
                for (String name : udtDefs) {
                    udts.add(typeDefs.get(name));
                }
                result.add("udts", udts);
            }
            if (!aoiDefs.isEmpty()) {
                JsonArray aois = new JsonArray();
                for (String name : aoiDefs) {
                    aois.add(typeDefs.get(name));
                }
                result.add("aois", aois);
            }

            JsonObject summary = new JsonObject();
            summary.addProperty("controllerTagCount", controllerTags.size());
            JsonObject programTagCounts = new JsonObject();
            for (ProgramBuild pb : programs.values()) {
                programTagCounts.addProperty(pb.name, pb.tags.size());
            }
            summary.add("programTagCounts", programTagCounts);
            summary.addProperty("udtDefCount", udtDefs.size());
            summary.addProperty("aoiDefCount", aoiDefs.size());
            summary.addProperty("aliasTagCount", counters.aliasTagCount);
            summary.addProperty("arrayTagCount", counters.arrayTagCount);
            summary.addProperty("skippedTagLines", counters.skippedTagLines);
            summary.addProperty("unknownTypeTags", counters.unknownTypeTags);
            summary.addProperty("unmodelledFbdTypes", counters.unmodelledFbdTypes);
            summary.addProperty("droppedNoneAccess", counters.droppedNoneAccess);
            summary.addProperty("structurallyClean", counters.structurallyClean());
            result.add("parseSummary", summary);

            return result;
        }
    }

    private static final class ProgramBuild {
        final String name;
        final JsonArray tags = new JsonArray();

        ProgramBuild(String name) {
            this.name = name;
        }
    }

    // ===========================================================================================
    // Line/keyword scanning helpers
    // ===========================================================================================

    private static String matchBeginKeyword(String trimmedLine) {
        for (String keyword : BLOCK_KEYWORDS.keySet()) {
            if (startsWithWord(trimmedLine, keyword)) {
                return keyword;
            }
        }
        return null;
    }

    private static boolean startsWithWord(String line, String word) {
        if (!line.regionMatches(true, 0, word, 0, word.length())) {
            return false;
        }
        if (line.length() == word.length()) {
            return true;
        }
        char next = line.charAt(word.length());
        return Character.isWhitespace(next) || next == '(';
    }

    /** @return the line's leading token - the characters before the first whitespace, {@code (},
     *      {@code :}, or {@code ;} (an END keyword line may be exactly {@code END_TAG} or carry
     *      trailing content; a declaration line's name stops at those delimiters). */
    private static String firstToken(String trimmedLine) {
        int end = 0;
        while (end < trimmedLine.length()) {
            char c = trimmedLine.charAt(end);
            if (Character.isWhitespace(c) || c == '(' || c == ':' || c == ';') {
                break;
            }
            end++;
        }
        return trimmedLine.substring(0, end);
    }

    /** Removes the contents of double- and single-quoted spans (quotes included), so keyword
     *  scans cannot match text inside descriptions or string literals. */
    private static String stripQuoted(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean inDouble = false;
        boolean inSingle = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                }
                continue;
            }
            if (c == '"') {
                inDouble = true;
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean containsWord(String line, String word) {
        if (word == null) {
            return false;
        }
        Pattern p = Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(word) + "(?![A-Za-z0-9_])");
        return p.matcher(line).find();
    }

    private static String extractName(String trimmedLine, String keyword) {
        String rest = trimmedLine.substring(keyword.length()).strip();
        if (rest.isEmpty()) {
            return null;
        }
        int end = 0;
        while (end < rest.length() && !Character.isWhitespace(rest.charAt(end)) && rest.charAt(end) != '(') {
            end++;
        }
        String name = rest.substring(0, end).strip();
        return name.isEmpty() ? null : name;
    }

    /**
     * Scans one physical line's characters, updating quote state ({@code "}/{@code '}) carried
     * across lines while skipping opaque content. {@code (* ... *)} block comments (the file
     * preamble, and defensively anywhere in opaque content) suspend quote tracking so an
     * apostrophe inside a comment cannot poison the state. Returns
     * {@code [inDouble, inSingle, inComment]} after the line.
     */
    private static boolean[] scanQuoteState(String line, boolean inDouble, boolean inSingle,
                                             boolean inComment) {
        for (int c = 0; c < line.length(); c++) {
            char ch = line.charAt(c);
            if (inComment) {
                if (ch == '*' && c + 1 < line.length() && line.charAt(c + 1) == ')') {
                    inComment = false;
                    c++;
                }
                continue;
            }
            if (inSingle) {
                if (ch == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                if (ch == '"') {
                    inDouble = false;
                }
                continue;
            }
            if (ch == '"') {
                inDouble = true;
            } else if (ch == '\'') {
                inSingle = true;
            } else if (ch == '(' && c + 1 < line.length() && line.charAt(c + 1) == '*') {
                inComment = true;
                c++;
            }
        }
        // Single-quote state is deliberately line-local: an ST_ROUTINE body uses a bare ' as a
        // line-comment marker (§2.8), which would otherwise poison the carried state until the
        // next apostrophe anywhere in the file. Single-quoted L5K string literals never span
        // physical lines (initialisers wrap at commas BETWEEN elements, outside quotes - §2.5),
        // so dropping the carry is safe; double-quoted rung comments genuinely span lines and
        // keep their carry.
        return new boolean[] {inDouble, false, inComment};
    }

    /**
     * Consumes a begin-keyword header whose parenthesised attribute list may wrap across many
     * physical lines (L5K-GRAMMAR.md §1.2 rule 3): returns the index of the line on which the
     * header's bracketing closes (paren depth back to 0 outside quotes), or {@code startIdx}
     * unchanged when the begin line carries no unclosed {@code (}.
     */
    private static int consumeHeader(String[] lines, int startIdx) {
        boolean inDouble = false;
        boolean inSingle = false;
        int parenDepth = 0;

        for (int i = startIdx; i < lines.length; i++) {
            String line = lines[i];
            for (int c = 0; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (inSingle) {
                    if (ch == '\'') {
                        inSingle = false;
                    }
                    continue;
                }
                if (inDouble) {
                    if (ch == '"') {
                        inDouble = false;
                    }
                    continue;
                }
                switch (ch) {
                    case '"' -> inDouble = true;
                    case '\'' -> inSingle = true;
                    case '(' -> parenDepth++;
                    case ')' -> parenDepth--;
                    default -> { }
                }
            }
            if (parenDepth <= 0 && !inDouble && !inSingle) {
                return i;
            }
        }
        throw new L5KStructuralException(
            "Unclosed attribute list in block header starting at line " + (startIdx + 1)
                + " - no matching ')' before EOF");
    }

    /** Result of {@link #accumulateStatement}: the joined statement text (terminator included,
     *  block comments stripped), the index of the next line to resume scanning from, and any
     *  non-blank, non-comment content found on the SAME physical line after the terminator
     *  (FIX-C) - {@code null} when there is none. */
    private record StatementScan(String text, int nextLine, String residue) { }

    /**
     * Accumulates physical lines starting at {@code startIdx} into one logical statement,
     * terminating at the first {@code ;} that is outside a quoted string and outside {@code [ ]}/
     * {@code ( )} nesting (L5K-GRAMMAR.md §1.4 R3/R4).
     *
     * <p><b>FIX-B:</b> {@code (* ... *)} block comments are recognised and their content is
     * stripped from the accumulated statement entirely - consistent with how
     * {@link #scanQuoteState} handles them in the opaque path - so a comment between two tag
     * declarations (or inside one, between attributes) never merges into a statement's text, never
     * perturbs bracket/paren depth, and never hides/fakes a terminator. Comment state carries
     * across physical lines like quote state does, so a multi-line block comment is handled too.
     *
     * <p><b>FIX-C:</b> content following a same-line terminator is never silently discarded: it is
     * returned as {@link StatementScan#residue()} for the caller to count and WARN on (§5.2) -
     * this parser deliberately does not attempt to re-parse it as a further statement (real files
     * are one-declaration-per-line, so this is a defensive net, not the common case).
     */
    private static StatementScan accumulateStatement(String[] lines, int startIdx) {
        StringBuilder sb = new StringBuilder();
        boolean inDouble = false;
        boolean inSingle = false;
        boolean inComment = false;
        int bracketDepth = 0;
        int parenDepth = 0;

        int i = startIdx;
        while (i < lines.length) {
            String line = lines[i];
            StringBuilder clean = new StringBuilder(line.length());

            int c = 0;
            while (c < line.length()) {
                char ch = line.charAt(c);

                if (inComment) {
                    if (ch == '*' && c + 1 < line.length() && line.charAt(c + 1) == ')') {
                        inComment = false;
                        c += 2;
                    } else {
                        c++;
                    }
                    continue;
                }
                if (inSingle) {
                    clean.append(ch);
                    if (ch == '\'') {
                        inSingle = false;
                    }
                    c++;
                    continue;
                }
                if (inDouble) {
                    clean.append(ch);
                    if (ch == '"') {
                        inDouble = false;
                    }
                    c++;
                    continue;
                }
                if (ch == '(' && c + 1 < line.length() && line.charAt(c + 1) == '*') {
                    inComment = true;
                    c += 2;
                    continue;
                }

                clean.append(ch);
                switch (ch) {
                    case '"' -> inDouble = true;
                    case '\'' -> inSingle = true;
                    case '[' -> bracketDepth++;
                    case ']' -> bracketDepth = Math.max(0, bracketDepth - 1);
                    case '(' -> parenDepth++;
                    case ')' -> parenDepth = Math.max(0, parenDepth - 1);
                    case ';' -> {
                        if (bracketDepth == 0 && parenDepth == 0) {
                            sb.append(clean);
                            String residue = extractResidue(line.substring(c + 1));
                            return new StatementScan(sb.toString(), i + 1, residue);
                        }
                    }
                    default -> { }
                }
                c++;
            }
            sb.append(clean).append(' ');
            i++;
        }

        throw new L5KStructuralException(
            "Unterminated statement starting at line " + (startIdx + 1)
                + " - no ';' found before EOF (bracket/quote state never closed)");
    }

    /**
     * FIX-C: {@code afterTerminator} is the raw text following a statement's {@code ;} on its own
     * physical line. Returns {@code null} when nothing but whitespace and/or a block
     * comment remains (the ordinary, expected case); otherwise the trimmed raw residue, so the
     * caller can WARN with the actual offending text.
     */
    private static String extractResidue(String afterTerminator) {
        String withoutComments = stripLineComments(afterTerminator).strip();
        return withoutComments.isEmpty() ? null : afterTerminator.strip();
    }

    /** Single-line {@code (* ... *)} comment stripper (quote-aware) used only by
     *  {@link #extractResidue} to decide whether same-line trailing text is "real" content or
     *  just a trailing comment - residue is by construction confined to one physical line, so no
     *  cross-line comment state needs to be carried here. */
    private static String stripLineComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inComment = false;
        int c = 0;
        while (c < s.length()) {
            char ch = s.charAt(c);
            if (inComment) {
                if (ch == '*' && c + 1 < s.length() && s.charAt(c + 1) == ')') {
                    inComment = false;
                    c += 2;
                } else {
                    c++;
                }
                continue;
            }
            if (inSingle) {
                out.append(ch);
                if (ch == '\'') {
                    inSingle = false;
                }
                c++;
                continue;
            }
            if (inDouble) {
                out.append(ch);
                if (ch == '"') {
                    inDouble = false;
                }
                c++;
                continue;
            }
            if (ch == '(' && c + 1 < s.length() && s.charAt(c + 1) == '*') {
                inComment = true;
                c += 2;
                continue;
            }
            out.append(ch);
            if (ch == '"') {
                inDouble = true;
            } else if (ch == '\'') {
                inSingle = true;
            }
            c++;
        }
        return out.toString();
    }

    private static String stripTerminator(String statementText) {
        int idx = statementText.lastIndexOf(';');
        return (idx < 0 ? statementText : statementText.substring(0, idx)).strip();
    }

    private static String truncateForLog(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    // ===========================================================================================
    // §5.1 Rung-token tripwire
    // ===========================================================================================

    /** Implements the L5K-GRAMMAR.md §5.1 hard-fail tripwire and §5.2 skip counter for a
     *  statement that reached a whitelisted context but does not match any tag-declaration shape
     *  (non-alias, alias, or DATATYPE member). */
    private static final class RungTripwire {
        private RungTripwire() {
        }

        static void check(String body, String fileName, int lineNo, Counters counters) {
            int colon = body.indexOf(':');
            String candidateName = (colon < 0 ? body : body.substring(0, colon)).strip();
            String afterColon = colon < 0 ? "" : body.substring(colon + 1).strip();
            String firstWord = firstWord(afterColon).toUpperCase(java.util.Locale.ROOT);

            if (RUNG_TYPE_NAMES.contains(candidateName) || LADDER_MNEMONICS.contains(firstWord)) {
                counters.tripwireFired = true;
                throw new L5KStructuralException(
                    "line " + lineNo + " in '" + fileName + "': rung-shaped statement '"
                        + truncate(body) + "' reached a tag-parsing context - the block stack has "
                        + "failed to bound a ROUTINE/rung region (L5K-GRAMMAR.md §5.1 tripwire)");
            }

            counters.skippedTagLines++;
            logger.warn(
                "L5K '{}' line {}: statement '{}' inside a tag-bearing block matches neither a "
                    + "tag, alias, nor DATATYPE-member declaration - skipping (skippedTagLines={})",
                fileName, lineNo, truncate(body), counters.skippedTagLines);
        }

        private static String firstWord(String s) {
            int end = 0;
            while (end < s.length() && (Character.isLetterOrDigit(s.charAt(end)) || s.charAt(end) == '_')) {
                end++;
            }
            return s.substring(0, end);
        }

        private static String truncate(String s) {
            return s.length() > 80 ? s.substring(0, 80) + "..." : s;
        }
    }

    // ===========================================================================================
    // Tag-line shape classification (§2.1 non-alias, §2.2 alias)
    // ===========================================================================================

    /** The parsed shape of a NAME : TYPE / NAME OF TARGET statement body, before type lookup. */
    private static final class TagLineShape {
        final String name;
        final boolean alias;
        final String aliasTarget;
        final String typeToken;
        final String dimensions;
        final String attrsRaw;
        final String initValueRaw;

        private TagLineShape(String name, boolean alias, String aliasTarget, String typeToken,
                              String dimensions, String attrsRaw, String initValueRaw) {
            this.name = name;
            this.alias = alias;
            this.aliasTarget = aliasTarget;
            this.typeToken = typeToken;
            this.dimensions = dimensions;
            this.attrsRaw = attrsRaw;
            this.initValueRaw = initValueRaw;
        }

        /** @return the parsed shape, or {@code null} if {@code body} matches neither the
         *      non-alias nor the alias tag-declaration grammar. */
        static TagLineShape classify(String body) {
            String trimmed = body.strip();

            // Alias form first (§2.2): "<name> OF <target> [(attrs)]". Checked before the
            // non-alias split since an alias line never contains " : ".
            Matcher aliasMatcher = ALIAS_PATTERN.matcher(trimmed);
            if (aliasMatcher.matches()) {
                return new TagLineShape(aliasMatcher.group(1), true, aliasMatcher.group(2),
                    null, null, aliasMatcher.group(3), null);
            }

            // Non-alias form (§2.1): mandatory " : " (space-colon-space) split - the type token
            // may itself contain colons (module reference types), so do not split on bare ':'.
            int splitIdx = trimmed.indexOf(" : ");
            if (splitIdx < 0) {
                return null;
            }
            String name = trimmed.substring(0, splitIdx).strip();
            if (!NAME_PATTERN.matcher(name).matches()) {
                return null;
            }
            String rest = trimmed.substring(splitIdx + 3).strip();

            int typeEnd = 0;
            while (typeEnd < rest.length()
                && !Character.isWhitespace(rest.charAt(typeEnd))
                && rest.charAt(typeEnd) != '[') {
                typeEnd++;
            }
            String typeToken = rest.substring(0, typeEnd);
            if (typeToken.isEmpty()) {
                return null;
            }
            String remaining = rest.substring(typeEnd);

            String dims = null;
            if (remaining.startsWith("[")) {
                int close = remaining.indexOf(']');
                if (close < 0) {
                    return null;
                }
                dims = remaining.substring(1, close);
                remaining = remaining.substring(close + 1);
            }
            remaining = remaining.strip();

            String attrs = null;
            if (remaining.startsWith("(")) {
                int close = matchingParen(remaining, 0);
                if (close < 0) {
                    return null;
                }
                attrs = remaining.substring(1, close);
                remaining = remaining.substring(close + 1).strip();
            }

            String initValue = null;
            if (remaining.startsWith(":=")) {
                initValue = remaining.substring(2).strip();
            }

            return new TagLineShape(name, false, null, typeToken, dims, attrs, initValue);
        }

        private static int matchingParen(String s, int openIdx) {
            int depth = 0;
            boolean inSingle = false;
            boolean inDouble = false;
            for (int i = openIdx; i < s.length(); i++) {
                char c = s.charAt(i);
                if (inSingle) {
                    if (c == '\'') {
                        inSingle = false;
                    }
                    continue;
                }
                if (inDouble) {
                    if (c == '"') {
                        inDouble = false;
                    }
                    continue;
                }
                if (c == '\'') {
                    inSingle = true;
                } else if (c == '"') {
                    inDouble = true;
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        return i;
                    }
                }
            }
            return -1;
        }

        private static final Pattern ALIAS_PATTERN =
            Pattern.compile("^(\\S+)\\s+OF\\s+([^\\s(]+)\\s*(?:\\((.*)\\))?$",
                Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    }

    // ===========================================================================================
    // Attribute parsing (§2.4)
    // ===========================================================================================

    /** Parses a {@code key := value, key2 := value2, ...} attribute list into a case-insensitive
     *  lookup, respecting quotes and nested {@code [ ] { } ( )} so commas inside a description or
     *  a {@code DataExchangeId := {...}} literal do not split the list incorrectly. */
    private static final class Attributes {
        private final Map<String, String> values = new LinkedHashMap<>();

        static Attributes parse(String raw) {
            Attributes attrs = new Attributes();
            if (raw == null || raw.isBlank()) {
                return attrs;
            }
            List<String> segments = splitTopLevel(raw, ',');
            for (String segment : segments) {
                int eq = findAssignment(segment);
                if (eq < 0) {
                    continue;
                }
                String key = segment.substring(0, eq).strip();
                String value = segment.substring(eq + 2).strip();
                attrs.values.put(key.toUpperCase(java.util.Locale.ROOT), value);
            }
            return attrs;
        }

        String get(String key) {
            return values.get(key.toUpperCase(java.util.Locale.ROOT));
        }

        boolean has(String key) {
            return values.containsKey(key.toUpperCase(java.util.Locale.ROOT));
        }

        private static int findAssignment(String s) {
            for (int i = 0; i < s.length() - 1; i++) {
                if (s.charAt(i) == ':' && s.charAt(i + 1) == '=') {
                    return i;
                }
            }
            return -1;
        }

        private static List<String> splitTopLevel(String s, char sep) {
            List<String> out = new ArrayList<>();
            int depth = 0;
            boolean inSingle = false;
            boolean inDouble = false;
            int start = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (inSingle) {
                    if (c == '\'') {
                        inSingle = false;
                    }
                    continue;
                }
                if (inDouble) {
                    if (c == '"') {
                        inDouble = false;
                    }
                    continue;
                }
                switch (c) {
                    case '\'' -> inSingle = true;
                    case '"' -> inDouble = true;
                    case '[', '{', '(' -> depth++;
                    case ']', '}', ')' -> depth = Math.max(0, depth - 1);
                    default -> { }
                }
                if (c == sep && depth == 0) {
                    out.add(s.substring(start, i));
                    start = i + 1;
                }
            }
            out.add(s.substring(start));
            return out;
        }
    }

    /** ExternalAccess disposition (L5K-GRAMMAR.md §2.4, ADDRESSING.md §3.12). */
    private enum Access { NONE, READ_ONLY, READ_WRITE }

    private static Access classifyExternalAccess(Attributes attrs) {
        String raw = attrs.get("ExternalAccess");
        if (raw == null) {
            return Access.READ_WRITE;
        }
        String v = raw.strip();
        if (v.equalsIgnoreCase("None")) {
            return Access.NONE;
        }
        if (v.equalsIgnoreCase("Read Only")) {
            return Access.READ_ONLY;
        }
        return Access.READ_WRITE;
    }

    private static boolean isConstant(Attributes attrs) {
        String v = attrs.get("Constant");
        if (v == null) {
            return false;
        }
        String s = v.strip();
        return !(s.equalsIgnoreCase("0") || s.equalsIgnoreCase("No") || s.equalsIgnoreCase("false"));
    }

    // ===========================================================================================
    // DATATYPE member grammar (§2.6) - TYPE NAME order (opposite of a tag line)
    // ===========================================================================================

    private static final class DatatypeMemberParser {
        private static final Pattern BIT_PATTERN = Pattern.compile(
            "^BIT\\s+(\\S+)\\s+(\\S+?)\\s*:\\s*(\\d+)\\s*(?:\\((.*)\\))?$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

        private DatatypeMemberParser() {
        }

        static void parse(String body, JsonArray members, Counters counters, String fileName, int lineNo) {
            Matcher bitMatcher = BIT_PATTERN.matcher(body);
            if (bitMatcher.matches()) {
                JsonObject member = new JsonObject();
                member.addProperty("name", bitMatcher.group(1));
                member.addProperty("data_type", "BOOL");
                members.add(member);
                return;
            }

            String head = body;
            String attrsRaw = null;
            int parenIdx = topLevelIndexOf(body, '(');
            if (parenIdx >= 0) {
                head = body.substring(0, parenIdx).strip();
                int close = TagLineShape.matchingParen(body, parenIdx);
                attrsRaw = close > parenIdx ? body.substring(parenIdx + 1, close) : "";
            }

            String[] tokens = head.strip().split("\\s+");
            if (tokens.length != 2) {
                RungTripwire.check(body, fileName, lineNo, counters);
                return;
            }

            String typeToken = tokens[0];
            String nameAndDims = tokens[1];
            int bracket = nameAndDims.indexOf('[');
            String name = bracket < 0 ? nameAndDims : nameAndDims.substring(0, bracket);
            String dims = null;
            if (bracket >= 0 && nameAndDims.endsWith("]")) {
                dims = nameAndDims.substring(bracket + 1, nameAndDims.length() - 1);
            }

            Attributes attrs = Attributes.parse(attrsRaw);
            boolean hidden = name.startsWith("ZZZZ") || "1".equals(attrs.get("Hidden"));
            if (hidden) {
                // Hidden bit-host bytes are retained for BIT-member backing but never browsable
                // (L5K-GRAMMAR.md §2.6) - dropped from the member list entirely, same as v10.
                return;
            }

            JsonObject member = new JsonObject();
            member.addProperty("name", name);
            member.addProperty("data_type", normalizeDataType(typeToken));
            if (dims != null && !dims.isEmpty()) {
                member.addProperty("dimensions", dims);
            }
            members.add(member);
        }

        private static int topLevelIndexOf(String s, char target) {
            boolean inSingle = false;
            boolean inDouble = false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (inSingle) {
                    if (c == '\'') {
                        inSingle = false;
                    }
                    continue;
                }
                if (inDouble) {
                    if (c == '"') {
                        inDouble = false;
                    }
                    continue;
                }
                if (c == '\'') {
                    inSingle = true;
                } else if (c == '"') {
                    inDouble = true;
                } else if (c == target) {
                    return i;
                }
            }
            return -1;
        }
    }

    // ===========================================================================================
    // Tag / AOI-member construction from a classified shape (§2.1/§2.2/§2.7, §3.2-§3.4)
    // ===========================================================================================

    private static final class TagStatementBuilder {
        private TagStatementBuilder() {
        }

        /** Builds a controller/program tag JsonObject, or {@code null} if it must be omitted
         *  (ExternalAccess=None, §3.12). Expands UDT/AOI/predefined instances recursively. */
        static JsonObject buildTag(TagLineShape shape, Map<String, JsonObject> typeDefs,
                                    Counters counters, String fileName, int lineNo) {
            if (shape == null) {
                return null;
            }

            // §5.1 tripwire, shape-parsed variant: a controller/program tag whose NAME is a rung
            // type or whose TYPE is a ladder mnemonic means rung text reached a tag context in a
            // form that happened to classify - never emit it, fail the file loudly.
            if (RUNG_TYPE_NAMES.contains(shape.name)) {
                counters.tripwireFired = true;
                throw new L5KStructuralException(
                    "line " + lineNo + " in '" + fileName + "': tag named '" + shape.name
                        + "' matches a rung type - rung text has reached a tag context "
                        + "(L5K-GRAMMAR.md §5.1 tripwire)");
            }
            if (!shape.alias
                && LADDER_MNEMONICS.contains(shape.typeToken.toUpperCase(java.util.Locale.ROOT))) {
                counters.tripwireFired = true;
                throw new L5KStructuralException(
                    "line " + lineNo + " in '" + fileName + "': tag '" + shape.name
                        + "' has ladder mnemonic '" + shape.typeToken + "' as its data type - rung "
                        + "text has reached a tag context (L5K-GRAMMAR.md §5.1 tripwire)");
            }

            Attributes attrs = Attributes.parse(shape.attrsRaw);

            if (shape.alias) {
                Access access = classifyExternalAccess(attrs);
                if (access == Access.NONE) {
                    counters.droppedNoneAccess++;
                    return null;
                }
                counters.aliasTagCount++;
                JsonObject tag = new JsonObject();
                tag.addProperty("name", shape.name);
                tag.addProperty("alias_for", shape.aliasTarget);
                tag.addProperty("data_type", resolveAliasType(shape.aliasTarget, attrs));
                if (access == Access.READ_ONLY) {
                    tag.addProperty("read_only", true);
                }
                return tag;
            }

            Access access = classifyExternalAccess(attrs);
            if (access == Access.NONE) {
                counters.droppedNoneAccess++;
                return null;
            }

            JsonObject tag = new JsonObject();
            tag.addProperty("name", shape.name);
            String normalizedType = normalizeDataType(shape.typeToken);
            tag.addProperty("data_type", normalizedType);

            if (access == Access.READ_ONLY || isConstant(attrs)) {
                tag.addProperty("read_only", true);
            }
            if (isConstant(attrs)) {
                tag.addProperty("constant", true);
            }

            boolean isArray = shape.dimensions != null && !shape.dimensions.isEmpty();
            if (isArray) {
                tag.addProperty("dimensions", shape.dimensions);
                tag.addProperty("isArray", true);
                counters.arrayTagCount++;
            }

            boolean expanded = expandIfKnownType(tag, normalizedType, typeDefs, counters, fileName, lineNo);

            if (!isArray && !expanded && shape.initValueRaw != null && !shape.initValueRaw.startsWith("[")) {
                // Scope note (§2.5): aggregate ([...]) initialisers are tokenised past correctly
                // but not interpreted in v10.1; only a bare atomic scalar literal is surfaced.
                tag.addProperty("initial_value", shape.initValueRaw);
            }

            return tag;
        }

        /** Builds one AOI PARAMETERS/LOCAL_TAGS member definition entry, or {@code null} if it
         *  must be excluded from the definition entirely (an InOut parameter - ADDRESSING.md
         *  §3.3: "InOut parameters are references, not backing-tag members"). ExternalAccess=None
         *  members ARE kept (marked hidden) so {@link #expandIfKnownType} can skip them per
         *  instance, mirroring how {@link L5XParser} treats UDT/AOI members. */
        static JsonObject buildAoiMember(TagLineShape shape, boolean isParameter, Counters counters,
                                          String fileName, int lineNo) {
            if (shape == null || shape.alias) {
                return null;
            }
            // §5.1 mnemonic-type tripwire also guards AOI member contexts (D11 hardening): a
            // parameter/local whose "type" is a ladder mnemonic can only mean rung leakage.
            // (The rung-type NAME check is deliberately not applied here - short parameter names
            // like I or D are plausible in legitimate AOI definitions, and raw rung text never
            // classifies as a NAME : TYPE shape anyway - it fails the classify step and hits
            // RungTripwire.check instead.)
            if (LADDER_MNEMONICS.contains(shape.typeToken.toUpperCase(java.util.Locale.ROOT))) {
                counters.tripwireFired = true;
                throw new L5KStructuralException(
                    "line " + lineNo + " in '" + fileName + "': AOI member '" + shape.name
                        + "' has ladder mnemonic '" + shape.typeToken + "' as its data type "
                        + "(L5K-GRAMMAR.md §5.1 tripwire)");
            }
            Attributes attrs = Attributes.parse(shape.attrsRaw);
            String usage = attrs.get("Usage");
            if (isParameter && "InOut".equalsIgnoreCase(usage)) {
                return null;
            }

            JsonObject member = new JsonObject();
            member.addProperty("name", shape.name);
            member.addProperty("data_type", normalizeDataType(shape.typeToken));
            if (shape.dimensions != null && !shape.dimensions.isEmpty()) {
                member.addProperty("dimensions", shape.dimensions);
            }
            if (usage != null && !usage.isBlank()) {
                member.addProperty("usage", usage);
            }

            Access access = classifyExternalAccess(attrs);
            if (access == Access.NONE) {
                member.addProperty("hidden", true);
                counters.droppedNoneAccess++;
            } else if (access == Access.READ_ONLY) {
                member.addProperty("read_only", true);
            }
            return member;
        }

        /** @return {@code true} if {@code typeName} was found in the type registry and
         *      {@code udt_members} was added to {@code holder}. */
        static boolean expandIfKnownType(JsonObject holder, String typeName,
                                          Map<String, JsonObject> typeDefs, Counters counters,
                                          String fileName, int lineNo) {
            if (ATOMIC_TYPES.contains(typeName.toUpperCase(java.util.Locale.ROOT))) {
                return false;
            }
            JsonObject def = typeDefs.get(typeName);
            if (def == null) {
                if (typeName.toUpperCase(java.util.Locale.ROOT).startsWith("FBD_")) {
                    counters.unmodelledFbdTypes++;
                    logger.warn("L5K '{}' line {}: unmodelled FBD type '{}' - emitting as an opaque "
                        + "leaf (unmodelledFbdTypes={})", fileName, lineNo, typeName,
                        counters.unmodelledFbdTypes);
                } else {
                    counters.unknownTypeTags++;
                    logger.warn("L5K '{}' line {}: unrecognised type '{}' - emitting as an opaque "
                        + "leaf (unknownTypeTags={})", fileName, lineNo, typeName,
                        counters.unknownTypeTags);
                }
                return false;
            }
            expandRecursive(holder, def, typeDefs, 0);
            return true;
        }

        private static void expandRecursive(JsonObject holder, JsonObject def,
                                             Map<String, JsonObject> typeDefs, int depth) {
            if (depth > 10) {
                logger.warn("Maximum UDT/AOI nesting depth exceeded for type '{}'",
                    def.has("name") ? def.get("name").getAsString() : "?");
                return;
            }
            JsonArray members = def.getAsJsonArray("members");
            JsonArray out = new JsonArray();
            for (int i = 0; i < members.size(); i++) {
                JsonObject memberDef = members.get(i).getAsJsonObject();
                if (memberDef.has("hidden") && memberDef.get("hidden").getAsBoolean()) {
                    continue;
                }
                JsonObject member = new JsonObject();
                String memberName = memberDef.get("name").getAsString();
                String memberType = memberDef.get("data_type").getAsString();
                member.addProperty("name", memberName);
                member.addProperty("data_type", memberType);
                if (memberDef.has("dimensions")) {
                    member.addProperty("dimensions", memberDef.get("dimensions").getAsString());
                }
                if (memberDef.has("read_only") && memberDef.get("read_only").getAsBoolean()) {
                    member.addProperty("read_only", true);
                }
                if (!ATOMIC_TYPES.contains(memberType.toUpperCase(java.util.Locale.ROOT))
                    && typeDefs.containsKey(memberType)) {
                    expandRecursive(member, typeDefs.get(memberType), typeDefs, depth + 1);
                } else {
                    // FIX-D (v10.1.0): align with L5XParser.expandUdtInstance, which sets a
                    // type-appropriate default initial_value on every atomic (non-expanding) leaf
                    // member so both formats feed AddressSpaceBuilder/getInitialValue() the same
                    // shape. (A verified residual: L5XParser.expandUdtInstance does NOT itself
                    // propagate a "usage" marker onto per-instance expanded members either - the
                    // "usage":"Local" annotation L5XParser.parseAOI adds lives only on the AOI
                    // TYPE DEFINITION's LocalTag entries (aois[].members[]), never on an
                    // instance's udt_members, in either parser, and AddressSpaceBuilder does not
                    // consume "usage" at all - so no usage marker is fabricated here; doing so
                    // would diverge from, not align with, L5X's actual instance-expansion shape.)
                    member.addProperty("initial_value", getDefaultValue(memberType));
                }
                out.add(member);
            }
            holder.add("udt_members", out);
        }

        /**
         * Best-effort data type for an alias tag's leaf node (§2.2 "minimum viable behaviour"):
         * a {@code .bit} target is always BOOL; otherwise the alias's own RADIX attribute (real
         * exports carry one on module-I/O-channel aliases, e.g. {@code RADIX := Float} on an
         * analog channel) picks the atomic family; failing both, an opaque DINT leaf. Full
         * alias-to-target value mirroring is a recorded Phase-2 non-goal.
         */
        private static String resolveAliasType(String target, Attributes attrs) {
            if (target != null && target.matches(".*\\.\\d+$")) {
                // "<base>.<bit>" always resolves to BOOL regardless of the base's own type.
                return "BOOL";
            }
            String radix = attrs.get("RADIX");
            if (radix != null) {
                if (radix.equalsIgnoreCase("Float") || radix.equalsIgnoreCase("Exponential")) {
                    return "REAL";
                }
                if (radix.equalsIgnoreCase("ASCII")) {
                    return "SINT";
                }
            }
            return "DINT";
        }
    }

    // ===========================================================================================
    // Built-in Rockwell type conversion (RockwellBuiltInTypes -> the JsonObject shape used above)
    // ===========================================================================================

    private static final class BuiltInTypeConverter {
        private BuiltInTypeConverter() {
        }

        static Map<String, JsonObject> convert() {
            Map<String, JsonObject> out = new LinkedHashMap<>();
            for (var entry : RockwellBuiltInTypes.createAll().entrySet()) {
                UDTDefinition def = entry.getValue();
                JsonObject json = new JsonObject();
                json.addProperty("name", def.getName());
                JsonArray members = new JsonArray();
                for (UDTDefinition.UDTMember m : def.getMembers()) {
                    JsonObject member = new JsonObject();
                    member.addProperty("name", m.getName());
                    member.addProperty("data_type", m.getDataType());
                    if (m.getDimensions() != null && !m.getDimensions().isEmpty()) {
                        member.addProperty("dimensions", m.getDimensions());
                    }
                    members.add(member);
                }
                json.add("members", members);
                out.put(entry.getKey(), json);
            }
            return out;
        }
    }
}
