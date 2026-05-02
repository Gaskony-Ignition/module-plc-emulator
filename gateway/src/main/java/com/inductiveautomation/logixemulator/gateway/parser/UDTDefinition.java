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
        members.add(new UDTMember(memberName, memberType));
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

        public UDTMember(String name, String dataType) {
            this.name = name;
            this.dataType = dataType;
        }

        public String getName() {
            return name;
        }

        public String getDataType() {
            return dataType;
        }
    }
}
