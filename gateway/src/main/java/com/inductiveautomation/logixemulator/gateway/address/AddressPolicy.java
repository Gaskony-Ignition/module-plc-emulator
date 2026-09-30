package com.inductiveautomation.logixemulator.gateway.address;

import java.util.List;

/**
 * Vendor-neutral seam that maps parsed-tag model constructs to OPC-UA NodeId identifier strings
 * and browse organisation. It is the ONLY place a vendor's addressing rules live — the
 * node-creation machinery ({@code AddressSpaceBuilder}) walks the vendor-neutral parsed-tag JSON
 * model and asks a policy for every identifier, so it contains no {@code Program:}-prefix,
 * BOOL-packing, member-separator or member-table logic of its own.
 *
 * <p>This boundary is the maintainer direction of 10/07/2026 (see
 * {@code docs/plans/ADDRESSING.md} §1): "Rockwell addressing is a per-vendor policy layered on a
 * vendor-neutral parsed-tag model." A future vendor parser (Siemens, Modbus, …) supplies its own
 * {@code AddressPolicy} without touching the builder. The only implementation in v10 is
 * {@link RockwellLogixPolicy}.
 *
 * <p>All identifiers returned are the {@code <identifier>} suffix that follows the driver's
 * standard {@code [DeviceName]} wrapper — the string that must match the real Logix driver across
 * a swap (ADDRESSING.md §0). Browse-folder labels are cosmetic (ADDRESSING.md §2.3): a child's
 * NodeId is always the canonical identifier below, never the folder-qualified path.
 */
public interface AddressPolicy {

    /**
     * Identifier prefix that a controller-scoped (global) tag name attaches to. For the Rockwell
     * modern Logix driver this is empty — controller tags are bare {@code TagName}
     * (ADDRESSING.md §3.1).
     *
     * @return the controller-scope prefix (may be empty)
     */
    String controllerScopePrefix();

    /**
     * Identifier prefix that a program-scoped tag name attaches to, given the program name.
     *
     * @param programName the Logix program name
     * @return the program-scope prefix (e.g. {@code Program:<programName>})
     */
    String programScopePrefix(String programName);

    /**
     * Cosmetic browse-folder label under which controller-scoped tags are grouped for human
     * navigation. Does NOT leak into any child NodeId (ADDRESSING.md §2.3, §3.15).
     *
     * @return the controller browse-folder label
     */
    String controllerBrowseFolder();

    /**
     * Cosmetic browse-folder label that parents the per-program browse folders.
     *
     * @return the programs browse-folder label
     */
    String programsBrowseFolder();

    /**
     * Scope selector that matches every program-scoped tag regardless of program (the bulk
     * "all programs" scope). For Rockwell this is the bare {@code Program:} selector.
     *
     * @return the all-programs scope selector
     */
    String allProgramsScopeSelector();

    /**
     * Whether a canonical identifier falls within a canonical scope selector. Used by the bulk
     * simulate-by-scope feature. An empty selector means the controller scope — under the v10
     * canonical scheme controller identifiers are bare, so controller membership is "not
     * program-scoped" rather than a positive prefix match.
     *
     * @param canonicalId a canonical NodeId identifier
     * @param scopeSelector a canonical scope selector: empty (controller scope),
     *     {@link #allProgramsScopeSelector()} (every program), a {@link #programScopePrefix}
     *     (one program), or any tag/member identifier prefix
     * @return {@code true} if the identifier is within the scope
     */
    boolean matchesScope(String canonicalId, String scopeSelector);

    /**
     * Join a parent identifier and a child (member or tag) name into a child identifier using the
     * vendor's member separator. When {@code parentId} is empty the child name is returned bare.
     *
     * @param parentId the parent identifier (may be empty for a controller-scope root)
     * @param childName the child member/tag name
     * @return the composed child identifier
     */
    String join(String parentId, String childName);

    /**
     * Map a canonical NodeId identifier back to the cosmetic browse-tree path (slash-separated,
     * rooted at the {@link #controllerBrowseFolder()} / {@link #programsBrowseFolder()} labels)
     * that the web tag browser displays.
     *
     * @param canonicalId a canonical NodeId identifier
     * @return the browse-tree path (e.g. {@code Controller:Global/Motor1/Speed} or
     *     {@code Programs/MainProgram/Counter})
     */
    String toBrowsePath(String canonicalId);

    /**
     * Compose an array-element identifier from a base identifier and a zero-based index tuple.
     * Multi-dimensional indices are combined per the vendor's convention (Rockwell: a single set
     * of brackets, comma-separated — ADDRESSING.md §3.5).
     *
     * @param baseId the array's base identifier
     * @param indices the zero-based index tuple (length = number of dimensions)
     * @return the element identifier (e.g. {@code base[i]} or {@code base[i,j]})
     */
    String arrayElement(String baseId, int[] indices);

    /**
     * @param dataType a parsed data-type string
     * @return {@code true} if the type is a single-bit BOOL, which the vendor packs specially when
     *     it appears as an array (ADDRESSING.md §3.8)
     */
    boolean isBoolType(String dataType);

    /**
     * Compose the packed bit identifier for a BOOL-array element (ADDRESSING.md §3.8). For
     * Rockwell, a {@code BOOL[N]} is DWORD-packed: element {@code N} maps to
     * {@code base[N/32].(N%32)} and only these bit nodes exist — a bare {@code base[N]} node
     * must not be created.
     *
     * @param baseId the BOOL array's base identifier
     * @param elementIndex the zero-based BOOL element index within the declared array
     * @return the packed bit identifier (e.g. element 33 -&gt; {@code base[1].1})
     */
    String boolArrayBit(String baseId, int elementIndex);

    /**
     * Parse a raw {@code Dimensions}/{@code Dimension} attribute into per-dimension sizes in
     * canonical (first-listed = outer) order. Accepts either the space-separated L5X tag form
     * ({@code "2 4"}) or a comma-separated form ({@code "2,4"}).
     *
     * @param dimensions the raw dimensions attribute
     * @return the per-dimension sizes, or an empty array if none could be parsed
     */
    int[] parseDimensions(String dimensions);

    /**
     * Enumerate every zero-based index tuple for an array of the given per-dimension sizes, in
     * canonical order (first dimension varies slowest — row-major), matching Studio 5000's
     * decorated {@code <Element Index="[i,j]">} ordering (ADDRESSING.md §3.5).
     *
     * @param dimensionSizes per-dimension sizes (length = number of dimensions)
     * @return every index tuple in canonical order
     */
    List<int[]> enumerateIndices(int[] dimensionSizes);
}
