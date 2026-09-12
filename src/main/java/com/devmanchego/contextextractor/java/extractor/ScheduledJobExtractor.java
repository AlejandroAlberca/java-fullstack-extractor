package com.devmanchego.contextextractor.java.extractor;

import com.devmanchego.contextextractor.java.model.ScheduledJobInfo;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts methods annotated with {@code @Scheduled} from a compilation unit.
 *
 * Handles both forms:
 * <ul>
 *   <li>{@code @Scheduled(cron = "0 * * * * *")}</li>
 *   <li>{@code @Scheduled(fixedRate = 5000)}</li>
 *   <li>{@code @Scheduled(fixedDelay = 10000, initialDelay = 1000)}</li>
 * </ul>
 */
public final class ScheduledJobExtractor {

    public List<ScheduledJobInfo> extract(CompilationUnit cu, String sourceFile) {
        List<ScheduledJobInfo> result = new ArrayList<>();
        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls ->
            cls.getMethods().forEach(method -> {
                method.getAnnotationByName("Scheduled").ifPresent(ann ->
                    result.add(buildInfo(cls, method, ann, sourceFile)));
            })
        );
        return result;
    }

    private ScheduledJobInfo buildInfo(ClassOrInterfaceDeclaration cls,
                                       MethodDeclaration method,
                                       AnnotationExpr ann,
                                       String sourceFile) {
        String className  = cls.getNameAsString();
        String methodName = method.getNameAsString();

        String cron = null, fixedRate = null, fixedDelay = null, initialDelay = null;

        if (ann instanceof NormalAnnotationExpr normal) {
            for (MemberValuePair pair : normal.getPairs()) {
                String val = unquote(pair.getValue().toString());
                switch (pair.getNameAsString()) {
                    case "cron"         -> cron         = val;
                    case "fixedRate",
                         "fixedRateString"   -> fixedRate  = val;
                    case "fixedDelay",
                         "fixedDelayString"  -> fixedDelay = val;
                    case "initialDelay",
                         "initialDelayString"-> initialDelay = val;
                }
            }
        } else if (ann instanceof SingleMemberAnnotationExpr single) {
            cron = unquote(single.getMemberValue().toString());
        }

        return new ScheduledJobInfo(className, methodName, sourceFile,
                cron, fixedRate, fixedDelay, initialDelay);
    }

    private String unquote(String s) {
        String t = s.trim();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }
}
