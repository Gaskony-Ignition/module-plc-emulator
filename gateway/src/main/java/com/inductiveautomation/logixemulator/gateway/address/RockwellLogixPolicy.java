package com.inductiveautomation.logixemulator.gateway.address;

import java.util.ArrayList;
import java.util.List;

/**
 * The Rockwell Allen-Bradley Logix addressing policy — the single place that holds every
 * Rockwell-specific NodeId rule (the {@code Program:} scope prefix, the {@code .} member
 * separator, the {@code [i,j]} multi-dimensional array form, and BOOL DWORD-packing).
 *
 * <p>Ground truth is IA's <em>modern</em> Allen-Bradley Logix driver (firmware v21+), not the
 * legacy ControlLogix driver: controller tags are bare {@code TagName} (no {@code Global.} or
 * {@code Controller:Global.} prefix), program tags carry a singular {@code Program:} selector, and
 * BOOL arrays are exposed as packed 32-bit DWORDs (ADDRESSING.md §2.2, §3.1-§3.8).
 *
 * <p>Stateless and immutable — safe to share as a singleton.
 */
public final class RockwellLogixPolicy implements AddressPolicy {

    /** Separator between a parent identifier and a nested member/tag name. */
    private static final String MEMBER_SEPARATOR = ".";

    /** The literal program-scope selector prefix (ADDRESSING.md §3.2). */
    private static final String PROGRAM_SELECTOR = "Program:";

    /** DWORD width used to pack BOOL arrays (ADDRESSING.md §3.8). */
    private static final int DWORD_BITS = 32;

    @Override
    public String controllerScopePrefix() {
        // Modern driver: controller tags are bare, no prefix (ADDRESSING.md §3.1).
        return "";
    }

    @Override
    public String programScopePrefix(String programName) {
        // Literal word "Program", a colon, then the program name (ADDRESSING.md §3.2).
        return PROGRAM_SELECTOR + programName;
    }

    @Override
    public String controllerBrowseFolder() {
        // Cosmetic label matching the driver-mirror browse tree (ADDRESSING.md §2.3, §3.15).
        return "Controller:Global";
    }

    @Override
    public String programsBrowseFolder() {
        return "Programs";
    }

    @Override
    public String allProgramsScopeSelector() {
        return PROGRAM_SELECTOR;
    }

    @Override
    public boolean matchesScope(String canonicalId, String scopeSelector) {
        if (canonicalId == null) {
            return false;
        }
        if (scopeSelector == null || scopeSelector.isEmpty()) {
            // Controller scope: bare identifiers, i.e. anything that is not program-scoped.
            return !canonicalId.startsWith(PROGRAM_SELECTOR);
        }
        if (scopeSelector.endsWith(":")) {
            // "Program:" — every program-scoped tag, regardless of program.
            return canonicalId.startsWith(scopeSelector);
        }
        // A concrete scope/tag prefix: match the node itself, its members, or its array elements —
        // never a longer sibling name ("Program:Main" must not match "Program:MainProgram.X").
        return canonicalId.equals(scopeSelector)
            || canonicalId.startsWith(scopeSelector + MEMBER_SEPARATOR)
            || canonicalId.startsWith(scopeSelector + "[");
    }

    @Override
    public String join(String parentId, String childName) {
        if (parentId == null || parentId.isEmpty()) {
            return childName;
        }
        return parentId + MEMBER_SEPARATOR + childName;
    }

    @Override
    public String toBrowsePath(String canonicalId) {
        if (canonicalId == null || canonicalId.isEmpty()) {
            return canonicalId;
        }
        if (canonicalId.startsWith(PROGRAM_SELECTOR)) {
            String rest = canonicalId.substring(PROGRAM_SELECTOR.length());
            int dot = rest.indexOf('.');
            if (dot < 0) {
                return programsBrowseFolder() + "/" + rest;
            }
            String programName = rest.substring(0, dot);
            String remainder = rest.substring(dot + 1).replace(MEMBER_SEPARATOR, "/");
            return programsBrowseFolder() + "/" + programName + "/" + remainder;
        }
        return controllerBrowseFolder() + "/" + canonicalId.replace(MEMBER_SEPARATOR, "/");
    }

    @Override
    public String arrayElement(String baseId, int[] indices) {
        StringBuilder sb = new StringBuilder(baseId).append('[');
        for (int i = 0; i < indices.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(indices[i]);
        }
        return sb.append(']').toString();
    }

    @Override
    public boolean isBoolType(String dataType) {
        if (dataType == null) {
            return false;
        }
        String t = dataType.trim().toUpperCase();
        return t.equals("BOOL") || t.equals("BOOLEAN") || t.equals("BIT");
    }

    @Override
    public String boolArrayBit(String baseId, int elementIndex) {
        // element N -> base[N/32].(N%32) (ADDRESSING.md §3.8). The bare base[N] node never exists.
        int wordIndex = elementIndex / DWORD_BITS;
        int bitIndex = elementIndex % DWORD_BITS;
        return baseId + "[" + wordIndex + "]" + MEMBER_SEPARATOR + bitIndex;
    }

    @Override
    public int[] parseDimensions(String dimensions) {
        if (dimensions == null || dimensions.trim().isEmpty()) {
            return new int[0];
        }
        // L5X tag Dimensions is space-separated ("2 4"); tolerate comma-separated too.
        String[] parts = dimensions.trim().split("[\\s,]+");
        List<Integer> sizes = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            try {
                int size = Integer.parseInt(part.trim());
                if (size > 0) {
                    sizes.add(size);
                }
            } catch (NumberFormatException e) {
                // Skip an unparseable dimension token; the caller falls back to a scalar.
                return new int[0];
            }
        }
        int[] result = new int[sizes.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = sizes.get(i);
        }
        return result;
    }

    @Override
    public List<int[]> enumerateIndices(int[] dimensionSizes) {
        List<int[]> tuples = new ArrayList<>();
        if (dimensionSizes.length == 0) {
            return tuples;
        }
        enumerate(dimensionSizes, 0, new int[dimensionSizes.length], tuples);
        return tuples;
    }

    /**
     * Row-major recursive enumeration: the first dimension varies slowest, matching the decorated
     * {@code <Element Index="[i,j]">} order (ADDRESSING.md §3.5).
     */
    private void enumerate(int[] sizes, int dim, int[] current, List<int[]> out) {
        if (dim == sizes.length) {
            out.add(current.clone());
            return;
        }
        for (int i = 0; i < sizes[dim]; i++) {
            current[dim] = i;
            enumerate(sizes, dim + 1, current, out);
        }
    }
}
