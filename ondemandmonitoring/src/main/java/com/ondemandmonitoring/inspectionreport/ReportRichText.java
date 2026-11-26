package com.ondemandmonitoring.inspectionreport;

import java.util.*;
import org.springframework.http.HttpStatus;

/** Bounded editor JSON only; never accepts HTML, links, scripts or remote media. */
final class ReportRichText {
    private static final Set<String> TYPES=Set.of("doc","paragraph","text","heading","bulletList","orderedList","listItem","hardBreak","blockquote","horizontalRule","table","tableRow","tableCell","tableHeader");
    private ReportRichText() {}

    static void validate(Map<String,Object> node, String plainText) {
        validateNode(node,0,new int[]{0});
        if(!"doc".equals(node.get("type")) || !normalize(text(node)).equals(normalize(plainText))) fail("Editor text differs from report text");
    }
    private static void validateNode(Map<String,Object> node,int depth,int[] count) {
        if(depth>15||++count[0]>5000||!(node.get("type") instanceof String)||!TYPES.contains(node.get("type")))fail("Unsupported editor node");
        if("table".equals(node.get("type"))&&(children(node).isEmpty()||children(node).size()>50))fail("Table requires 1–50 rows");
        if("tableRow".equals(node.get("type"))&&(children(node).isEmpty()||children(node).size()>6))fail("Table requires 1–6 columns");
        if(node.keySet().stream().anyMatch(k->!Set.of("type","text","attrs","marks","content").contains(k)))fail("Unsupported editor property");
        if(node.containsKey("text")&&(!(node.get("text") instanceof String)||!"text".equals(node.get("type"))))fail("Invalid editor text");
        if(node.containsKey("attrs")) {
            if(!(node.get("attrs") instanceof Map<?,?>))fail("Invalid editor attributes");
            var attrs=(Map<?,?>)node.get("attrs");
            for(var entry:attrs.entrySet()) {
                String key=entry.getKey().toString();Object value=entry.getValue();
                boolean valid=switch(key) {
                    case "level" -> "heading".equals(node.get("type"))&&integer(value,1,6);
                    case "start" -> "orderedList".equals(node.get("type"))&&integer(value,1,10000);
                    case "colspan","rowspan" -> Set.of("tableCell","tableHeader").contains(node.get("type"))&&integer(value,1,1);
                    case "colwidth" -> Set.of("tableCell","tableHeader").contains(node.get("type"))&&(value==null||(value instanceof List<?> widths&&widths.size()<=10&&widths.stream().allMatch(w->integer(w,1,2000))));
                    case "align" -> Set.of("tableCell","tableHeader").contains(node.get("type"))&&(value==null||Set.of("left","center","right").contains(value));
                    default -> false;
                };
                if(!valid)fail("Unsupported editor attributes");
            }
        }
        if(node.containsKey("marks")) {
            if(!(node.get("marks") instanceof List<?>))fail("Invalid editor marks");
            for(Object value:(List<?>)node.get("marks")) {
                if(!(value instanceof Map<?,?> mark)||!(mark.get("type") instanceof String)||!Set.of("bold","italic","strike","code","underline").contains(mark.get("type"))||mark.size()!=1)fail("Unsupported text mark");
            }
        }
        if(node.containsKey("content")) {
            if(!(node.get("content") instanceof List<?>))fail("Invalid editor content");
            for(Object value:(List<?>)node.get("content")) {
                if(!(value instanceof Map<?,?>))fail("Invalid editor child");
                validateNode((Map<String,Object>)value,depth+1,count);
            }
        }
    }
    static List<Map<String,Object>> children(Map<String,Object> node) {
        return node.get("content") instanceof List<?> list?(List<Map<String,Object>>)(List<?>)list:List.of();
    }
    static String text(Map<String,Object> node) {
        if("text".equals(node.get("type")))return Objects.toString(node.get("text"),"");
        if("hardBreak".equals(node.get("type")))return "\n";
        String separator=Set.of("paragraph","heading").contains(node.get("type"))?"":"\n";
        return String.join(separator,children(node).stream().map(ReportRichText::text).toList());
    }
    static String normalize(String value){return value.replaceAll("\\s+"," ").trim();}
    private static boolean integer(Object value,int min,int max){return value instanceof Number n&&n.doubleValue()==n.intValue()&&n.intValue()>=min&&n.intValue()<=max;}
    private static void fail(String message){throw InspectionReportService.error(HttpStatus.UNPROCESSABLE_ENTITY,message);}
}
