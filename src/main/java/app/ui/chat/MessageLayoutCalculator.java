package app.ui.chat;

import javax.swing.*;

import java.awt.*;

public class MessageLayoutCalculator {
    //这是限制文本的最大宽度
    private static final int MESSAGE_MAX_WIDTH=260;
    //限制短消息防止气泡被压成竖排
    private static final int MESSAGE_MIN_WIDTH=96;
    // 短消息按字符数估算宽度，比 JTextArea 的自动换行判断更适合中文。
    private static final int SHORT_MESSAGE_LENGTH=12;

    private MessageLayoutCalculator(){}

    static Result calculate(JTextArea messageArea,String content,Insets messageInsets){
        //先做强制换行，再根据换行后的文本计算最终气泡尺寸
        String wrappedContent=MessageTextWrapper.wrap(content);
        FontMetrics fontMetrics=messageArea.getFontMetrics(messageArea.getFont());

        int maxLineWidth=0;
        int maxLineChar=0;

        //找出最长和字符最多的一行
        for(String line:wrappedContent.split("\\R",-1)){
            maxLineWidth=Math.max(maxLineWidth,fontMetrics.stringWidth(line));
            maxLineChar=Math.max(maxLineChar,line.codePointCount(0,line.length()));
        }

        //定义气泡的最大和最小长度
        int maxTextWidth=MESSAGE_MAX_WIDTH-messageInsets.left-messageInsets.right;
        int minTextWidth=MESSAGE_MIN_WIDTH-messageInsets.left-messageInsets.right;
        int contentWidth=maxLineWidth+1;

        // 中文短句用字符宽度兜底，避免 JTextArea 在窄宽度下过早换行。
        if(maxLineChar>0 && maxLineChar<=SHORT_MESSAGE_LENGTH){
            contentWidth=Math.max(contentWidth,fontMetrics.charWidth('测')*maxLineChar);
        }

        //计算气泡宽度
        int textWidth=Math.max(minTextWidth,Math.min(contentWidth,maxTextWidth));
        int bubbleWidth=textWidth+messageInsets.left+messageInsets.right;

        //计算换行后的高度
        messageArea.setText(wrappedContent);
        messageArea.setSize(new Dimension(bubbleWidth,Short.MAX_VALUE));
        Dimension preferredSize=messageArea.getPreferredSize();
        preferredSize.width=bubbleWidth;

        return new Result(wrappedContent,preferredSize);
    }

    static class Result {
        private final String content;
        private final Dimension size;

        Result(String content, Dimension size) {
            this.content = content;
            this.size = size;
        }

        String getContent() {
            return content;
        }

        Dimension getSize() {
            return size;
        }
    }
}
