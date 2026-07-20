package app.ai;

/**
 * AI 工具层内部使用的极简 JSON 辅助类。
 * 项目暂时没有 JSON 依赖，所以这里只处理当前请求/流式响应需要的最小范围。
 */
final class AiJson {
    private AiJson(){
    }

    static String escape(String value){
        StringBuilder escaped=new StringBuilder();
        for(int index=0;index<value.length();index++){
            char character=value.charAt(index);
            switch(character){
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if(character<0x20){
                        escaped.append(String.format("\\u%04x",(int) character));
                    }else{
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    static String extractStringField(String json,String fieldName){
        String quotedField="\"" + fieldName + "\"";
        int fieldIndex=json.indexOf(quotedField);
        if(fieldIndex<0){
            return null;
        }

        int colonIndex=json.indexOf(':',fieldIndex+quotedField.length());
        if(colonIndex<0){
            return null;
        }

        int valueStart=skipWhitespace(json,colonIndex+1);
        if(valueStart>=json.length() || json.charAt(valueStart)!='"'){
            return null;
        }

        return readJsonString(json,valueStart);
    }

    private static int skipWhitespace(String text,int start){
        int index=start;
        while(index<text.length() && Character.isWhitespace(text.charAt(index))){
            index++;
        }
        return index;
    }

    private static String readJsonString(String json,int quoteIndex){
        StringBuilder value=new StringBuilder();
        for(int index=quoteIndex+1;index<json.length();index++){
            char character=json.charAt(index);
            if(character=='"'){
                return value.toString();
            }

            if(character!='\\'){
                value.append(character);
                continue;
            }

            if(index+1>=json.length()){
                return value.toString();
            }

            char escaped=json.charAt(++index);
            switch(escaped){
                case '"' -> value.append('"');
                case '\\' -> value.append('\\');
                case '/' -> value.append('/');
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if(index+4<json.length()){
                        String hex=json.substring(index+1,index+5);
                        value.append((char) Integer.parseInt(hex,16));
                        index+=4;
                    }
                }
                default -> value.append(escaped);
            }
        }

        return value.toString();
    }
}
