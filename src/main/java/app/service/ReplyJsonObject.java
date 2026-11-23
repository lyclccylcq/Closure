package app.service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Strict reader for the reply contract's flat object of string fields. */
final class ReplyJsonObject {
    private final String source;
    private int position;

    private ReplyJsonObject(String source){
        this.source=source;
    }

    static Map<String,String> read(String source){
        return new ReplyJsonObject(source).readObject();
    }

    private Map<String,String> readObject(){
        Map<String,String> fields=new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if(!take('}')){
            do{
                String key=readString();
                expect(':');
                String value=readString();
                if(fields.putIfAbsent(key,value)!=null) throw invalid();
                skipWhitespace();
                if(take('}')) break;
                expect(',');
            }while(true);
        }
        skipWhitespace();
        if(position!=source.length()) throw invalid();
        return fields;
    }

    private String readString(){
        expect('"');
        StringBuilder value=new StringBuilder();
        while(position<source.length()){
            char c=source.charAt(position++);
            if(c=='"') return value.toString();
            if(c<0x20) throw invalid();
            if(c!='\\'){
                value.append(c);
                continue;
            }
            if(position==source.length()) throw invalid();
            char escape=source.charAt(position++);
            switch(escape){
                case '"', '\\', '/' -> value.append(escape);
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if(position+4>source.length()) throw invalid();
                    String hex=source.substring(position,position+4);
                    if(!hex.matches("[0-9a-fA-F]{4}")) throw invalid();
                    value.append((char)Integer.parseInt(hex,16));
                    position+=4;
                }
                default -> throw invalid();
            }
        }
        throw invalid();
    }

    private void expect(char value){
        skipWhitespace();
        if(!take(value)) throw invalid();
    }

    private boolean take(char value){
        if(position<source.length() && source.charAt(position)==value){
            position++;
            return true;
        }
        return false;
    }

    private void skipWhitespace(){
        while(position<source.length() && " \t\r\n".indexOf(source.charAt(position))>=0) position++;
    }

    private IllegalArgumentException invalid(){
        return new IllegalArgumentException("模型回复不是完整的 JSON 字符串对象");
    }
}
