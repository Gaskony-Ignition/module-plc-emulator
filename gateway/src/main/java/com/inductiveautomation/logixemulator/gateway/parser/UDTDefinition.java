package com.inductiveautomation.logixemulator.gateway.parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a User Defined Type (UDT) definition parsed from PLC files.
 * Also used for Add-On Instructions (AOIs) which expand similarly.
 */
public class UDTDefinition {

    private final String name;
    private final List<UDTMember> members;

    public UDTDefinition(String name) {
        this.name = name;
        this.members = new ArrayList<>();
    }

    public void addMember(String memberName, String memberType) {
        members.add(new UDTMember(memberName, memberType, null));
    }

    /**
     * Adds an array member (e.g. the STRING built-in's {@code DATA} member, a {@code SINT}
     * array of dimension 82 - ADDRESSING.md §3.10).
     *
     * @param memberName the member name
     * @param memberType the member's data type
     * @param dimensions the raw dimension string (e.g. {@code "82"}); {@code null}/empty for a
     *     scalar member
     */
    public void addMember(String memberName, String memberType, String dimensions) {
        members.add(new UDTMember(memberName, memberType, dimensions));
    }

    public String getName() {
        return name;
    }

    public List<UDTMember> getMembers() {
        return java.util.Collections.unmodifiableList(members);
    }

    /**
     * Represents a single member within a UDT definition.
     */
    public static class UDTMember {
        private final String name;
        private final String dataType;
        private final String dimensions;

        public UDTMember(String name, String dataType, String dimensions) {
            this.name = name;
            this.dataType = dataType;
            this.dimensions = dimensions;
        }

        public String getName() {
            return name;
        }

        public String getDataType() {
            return dataType;
        }

        /** @return the raw dimension string, or {@code null} for a scalar member. */
        public String getDimensions() {
            return dimensions;
        }
    }
}
