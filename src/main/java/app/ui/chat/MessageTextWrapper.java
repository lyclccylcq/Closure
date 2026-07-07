package app.ui.chat;

public class MessageTextWrapper {

    //强制换行的长度为18
    private static final int FORCE_WRAP_LENGTH=18;

    private MessageTextWrapper(){}

        static String wrap(String content){
            String[] lines=content.split("\\R",-1);
            StringBuilder wrappedContent=new StringBuilder();

            for(int i=0;i< lines.length;i++){
                if (i>0){
                    wrappedContent.append('\n');
                }
                wrappedContent.append(wrapLine(lines[i]));
            }

            return wrappedContent.toString();
        }

    private static String wrapLine(String line){
        // 按 code point 处理，避免 emoji 或特殊字符被拆成半个字符。
        int[] codePoints=line.codePoints().toArray();
        if(codePoints.length<=FORCE_WRAP_LENGTH){
            return line;
        }

        StringBuilder wrappedLine=new StringBuilder();
        for(int i=0;i<codePoints.length;i++){
            // 每 FORCE_WRAP_LENGTH 个字符插入一个换行，形成类似微信的长文本折行。
            if(i>0 && i%FORCE_WRAP_LENGTH==0){
                wrappedLine.append('\n');
            }
            wrappedLine.appendCodePoint(codePoints[i]);
        }

        return wrappedLine.toString();
    }


}
