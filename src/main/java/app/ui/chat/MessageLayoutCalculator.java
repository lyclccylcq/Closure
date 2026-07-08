package app.ui.chat;

import javax.swing.*;
import java.awt.*;

public class MessageLayoutCalculator {
    // 这是限制文本的最大宽度
    private static final int MESSAGE_MAX_WIDTH=260;
    // 限制短消息防止气泡被压成竖排
    private static final int MESSAGE_MIN_WIDTH=140;
    // 只有很短的消息才按字符数兜底，正常一句话优先按实际像素宽度计算。
    private static final int SHORT_MESSAGE_LENGTH=6;

    private MessageLayoutCalculator(){}

    static Result calculate(JTextArea messageArea,String content,Insets messageInsets){
        FontMetrics fontMetrics=messageArea.getFontMetrics(messageArea.getFont());

        // 最大/最小宽度指整个气泡，这里先扣掉左右内边距，得到文字可用宽度。
        int maxTextWidth=MESSAGE_MAX_WIDTH-messageInsets.left-messageInsets.right;
        int minTextWidth=MESSAGE_MIN_WIDTH-messageInsets.left-messageInsets.right;

        // 先按像素宽度做强制换行，再根据换行后的文本计算最终气泡尺寸。
        String wrappedContent=MessageTextWrapper.wrap(content,fontMetrics,maxTextWidth);

        int maxLineWidth=0;
        int maxLineChar=0;

        // 找出最宽的一行和字符最多的一行，分别用于宽度计算和短消息兜底。
        for(String line:wrappedContent.split("\\R",-1)){
            maxLineWidth=Math.max(maxLineWidth,fontMetrics.stringWidth(line));
            maxLineChar=Math.max(maxLineChar,line.codePointCount(0,line.length()));
        }

        // 多留一点余量，避免刚好卡边导致 JTextArea 又多换一行。
        int contentWidth=maxLineWidth+16;

        // 中文极短句用字符宽度兜底，避免 JTextArea 在窄宽度下过早换行。
        if(maxLineChar>0 && maxLineChar<=SHORT_MESSAGE_LENGTH){
            contentWidth=Math.max(contentWidth,fontMetrics.charWidth('测')*maxLineChar+16);
        }

        // 把文字宽度限制在最小和最大范围内，再加回内边距得到气泡宽度。
        int textWidth=Math.max(minTextWidth,Math.min(contentWidth,maxTextWidth));
        int bubbleWidth=textWidth+messageInsets.left+messageInsets.right;

        // 给定气泡宽度后，让 JTextArea 自己计算换行后需要的高度。
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
