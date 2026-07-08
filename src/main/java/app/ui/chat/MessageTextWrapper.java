package app.ui.chat;

import java.awt.*;

public class MessageTextWrapper {

    private MessageTextWrapper(){}

    static String wrap(String content,FontMetrics fontMetrics,int maxTextWidth){
        // 先保留用户原本输入的换行，再对每一行按像素宽度做强制换行。
        String[] lines=content.split("\\R",-1);
        StringBuilder wrappedContent=new StringBuilder();

        for(int i=0;i<lines.length;i++){
            if(i>0){
                wrappedContent.append('\n');
            }
            wrappedContent.append(wrapLine(lines[i],fontMetrics,maxTextWidth));
        }

        return wrappedContent.toString();
    }

    private static String wrapLine(String line,FontMetrics fontMetrics,int maxTextWidth){
        // 按 code point 处理，避免 emoji 或特殊字符被拆成半个字符。
        int[] codePoints=line.codePoints().toArray();
        StringBuilder wrappedLine=new StringBuilder();
        StringBuilder currentLine=new StringBuilder();

        for(int codePoint:codePoints){
            String currentChar=new String(Character.toChars(codePoint));
            String nextLine=currentLine.toString()+currentChar;

            if(currentLine.length()>0
                    && fontMetrics.stringWidth(nextLine)>maxTextWidth
                    && !isLineStartPunctuation(codePoint)){
                appendWrappedLine(wrappedLine,currentLine);
                currentLine.setLength(0);
            }

            currentLine.append(currentChar);
        }

        appendWrappedLine(wrappedLine,currentLine);
        return wrappedLine.toString();
    }

    private static boolean isLineStartPunctuation(int codePoint){
        return "，。！？、；：,.!?)]}）】》".indexOf(codePoint)>=0;
    }

    private static void appendWrappedLine(StringBuilder wrappedLine,StringBuilder line){
        if(line.length()==0){
            return;
        }
        if(wrappedLine.length()>0){
            wrappedLine.append('\n');
        }
        wrappedLine.append(line);
    }
}
