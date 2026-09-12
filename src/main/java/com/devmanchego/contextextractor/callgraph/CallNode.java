package com.devmanchego.contextextractor.callgraph;

import java.util.List;

/**
 * A node in the call graph for a single API flow.
 * Represents one class+method in the chain:
 * Controller → Service → Validator → Repository → Mapper
 */
public final class CallNode {

    public enum Role { CONTROLLER, SERVICE, VALIDATOR, REPOSITORY, MAPPER, UNKNOWN }

    private final String className;      // simple or qualified-short form when ambiguous
    private final String methodName;
    private final Role role;
    private final boolean transactional; // @Transactional on method or class
    private final String returnType;     // unwrapped declared return type; null if unknown
    private final List<String> implementedInterfaces; // simple names, e.g. ["IAccountService", "Serializable"]
    private final String superClass;                  // simple name of parent class; null if none or "Object"
    private final List<FlowCondition> conditions;     // filtered: only business-relevant (D1-D5 / R1-R6)
    private final List<FlowCondition> allConditions;  // unfiltered: all top-level if/else (loops still excluded)
    private final List<CallNode> callees;

    public CallNode(String className, String methodName, Role role,
                    boolean transactional, String returnType,
                    List<String> implementedInterfaces, String superClass,
                    List<FlowCondition> conditions, List<CallNode> callees) {
        this(className, methodName, role, transactional, returnType,
             implementedInterfaces, superClass, conditions, conditions, callees);
    }

    public CallNode(String className, String methodName, Role role,
                    boolean transactional, String returnType,
                    List<String> implementedInterfaces, String superClass,
                    List<FlowCondition> conditions, List<FlowCondition> allConditions,
                    List<CallNode> callees) {
        this.className            = className;
        this.methodName           = methodName;
        this.role                 = role;
        this.transactional        = transactional;
        this.returnType           = returnType;
        this.implementedInterfaces = List.copyOf(implementedInterfaces);
        this.superClass           = superClass;
        this.conditions           = List.copyOf(conditions);
        this.allConditions        = List.copyOf(allConditions);
        this.callees              = List.copyOf(callees);
    }

    public String getClassName()                      { return className; }
    public String getMethodName()                     { return methodName; }
    public Role getRole()                             { return role; }
    public boolean isTransactional()                  { return transactional; }
    public String getReturnType()                     { return returnType; }
    public List<String> getImplementedInterfaces()    { return implementedInterfaces; }
    public String getSuperClass()                     { return superClass; }
    public List<FlowCondition> getConditions()        { return conditions; }
    public List<FlowCondition> getAllConditions()      { return allConditions; }
    public List<CallNode> getCallees()                { return callees; }

    public String displayName() {
        return className + "#" + methodName + (transactional ? " [@Tx]" : "");
    }
}
