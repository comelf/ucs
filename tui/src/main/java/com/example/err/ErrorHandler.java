package com.example.err;

import com.example.util.AnsiUtil;
import com.example.util.lang.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ErrorHandler {
    private static final Logger logger = LoggerFactory.getLogger(ErrorHandler.class);

    public void handle(Exception e, String context) {
        String msg = (e instanceof TuiException ce)
                ? Messages.get(ce.getUserMessageKey())
                : Messages.get("msg.error") + " " + e.getMessage();
        logger.error("[{}] {}", context, e.getMessage(), e);
        System.out.println(AnsiUtil.boldRed(msg));
    }
}
