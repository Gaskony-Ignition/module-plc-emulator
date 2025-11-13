import com.inductiveautomation.plcsimulator.gateway.parser.L5KParser;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

public class TestL5KParser {
    public static void main(String[] args) {
        // Test L5K content with UDTs
        String testContent = """
CONTROLLER TestController (ProcessorType := "1756-L73",
                             Major := 20,
                             TimeSlice := 20,
                             ShareUnusedTimeSlice := 1,
                             CommPath := "AB_ETH-1\\192.168.1.100\\1\\0")

    DATATYPE ALARMS_GENERAL (FamilyType := NoFamily)
        SINT ZZZZZZZZZZALARMS_GEN0 (Hidden := 1);
        BIT ESTOP_TRIPPED ZZZZZZZZZZALARMS_GEN0 : 0;
        BIT PHASE_FAIL ZZZZZZZZZZALARMS_GEN0 : 1;
        BIT PLC_FAULT ZZZZZZZZZZALARMS_GEN0 : 2;
        BIT ALARM_ACK ZZZZZZZZZZALARMS_GEN0 : 3;
    END_DATATYPE

    DATATYPE AI_PLS (FamilyType := NoFamily)
        AI : REAL;
        Fault : BOOL;
        HiHi : BOOL;
        Hi : BOOL;
        Lo : BOOL;
        LoLo : BOOL;
    END_DATATYPE

    DATATYPE MOTOR_CONTROL (FamilyType := NoFamily)
        Start : BOOL;
        Stop : BOOL;
        Running : BOOL;
        Fault : BOOL;
        Speed : REAL;
        Current : REAL;
        Temperature : REAL;
    END_DATATYPE

    TAG
        _1SEC_PLS_T : TIMER;
        _24HR_MINUTES : DINT;
        ALARMS : ALARMS_GENERAL;
        AI_DOP201_DATA : AI_PLS;
        AI_PLS102_DATA : AI_PLS;
        MOTOR_1 : MOTOR_CONTROL;
        MOTOR_2 : MOTOR_CONTROL;
        SimpleTag1 : DINT;
        SimpleTag2 : REAL;
        BoolArray : BOOL[32];
    END_TAG

    PROGRAM MainProgram
        TAG
            LocalTag1 : INT;
            LocalTag2 : BOOL;
            LocalMotor : MOTOR_CONTROL;
        END_TAG
    END_PROGRAM

END_CONTROLLER
""";

        L5KParser parser = new L5KParser();
        JsonObject result = parser.parseContent(testContent, "test.l5k");

        if (result == null) {
            System.err.println("FAILED: Parser returned null");
            System.exit(1);
        }

        // Check controller name
        String controller = result.get("controller").getAsString();
        System.out.println("✓ Controller: " + controller);

        // Check UDT definitions
        if (result.has("udts")) {
            JsonArray udts = result.getAsJsonArray("udts");
            System.out.println("✓ Found " + udts.size() + " UDT definitions");
            for (JsonElement udtElem : udts) {
                JsonObject udt = udtElem.getAsJsonObject();
                String name = udt.get("name").getAsString();
                JsonArray members = udt.getAsJsonArray("members");
                System.out.println("  - UDT: " + name + " with " + members.size() + " members");
            }
        } else {
            System.err.println("✗ No UDTs found!");
        }

        // Check global tags
        JsonArray globalTags = result.getAsJsonArray("global_tags");
        System.out.println("\n✓ Found " + globalTags.size() + " global tags:");

        int udtInstanceCount = 0;
        int simpleTagCount = 0;

        for (JsonElement tagElem : globalTags) {
            JsonObject tag = tagElem.getAsJsonObject();
            String name = tag.get("name").getAsString();
            String dataType = tag.get("data_type").getAsString();

            if (tag.has("udt_members")) {
                JsonArray members = tag.getAsJsonArray("udt_members");
                System.out.println("  ✓ UDT Instance: " + name + " (" + dataType + ") with " + members.size() + " members:");
                for (JsonElement memberElem : members) {
                    JsonObject member = memberElem.getAsJsonObject();
                    System.out.println("      - " + member.get("name").getAsString() +
                                     " : " + member.get("data_type").getAsString());
                }
                udtInstanceCount++;
            } else {
                System.out.println("  - Simple tag: " + name + " : " + dataType);
                simpleTagCount++;
            }
        }

        System.out.println("\n=== SUMMARY ===");
        System.out.println("UDT Instances: " + udtInstanceCount);
        System.out.println("Simple Tags: " + simpleTagCount);

        // Check program tags
        if (result.has("programs")) {
            JsonArray programs = result.getAsJsonArray("programs");
            System.out.println("Programs: " + programs.size());
            for (JsonElement progElem : programs) {
                JsonObject prog = progElem.getAsJsonObject();
                String progName = prog.get("name").getAsString();
                JsonArray progTags = prog.getAsJsonArray("tags");
                System.out.println("  - " + progName + " with " + progTags.size() + " tags");

                // Check for UDT in program
                for (JsonElement tagElem : progTags) {
                    JsonObject tag = tagElem.getAsJsonObject();
                    if (tag.has("udt_members")) {
                        System.out.println("    ✓ Program has UDT instance: " +
                                         tag.get("name").getAsString());
                    }
                }
            }
        }

        // Final verdict
        if (udtInstanceCount >= 4) {
            System.out.println("\n✓✓✓ SUCCESS! UDT parsing is working correctly!");
            System.out.println("The parser correctly identified and expanded UDT instances.");
        } else {
            System.err.println("\n✗✗✗ FAILURE! Expected at least 4 UDT instances, got " + udtInstanceCount);
            System.exit(1);
        }
    }
}