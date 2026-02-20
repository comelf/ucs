package com.example.cmd;

import com.example.err.TuiException;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.stream.Collectors;

public class ShellRunner {

    /** 명령어를 실행하고 출력을 String으로 캡처하여 반환 */
    public String runQuiet(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String output = reader.lines().collect(Collectors.joining("\n"));
                p.waitFor();
                return output;
            }
        } catch (Exception e) {
            throw new TuiException(e.getMessage(), "msg.exec_fail", e);
        }
    }

    /** 명령어를 inheritIO로 실행하여 터미널에 직접 출력 */
    public void runInteractive(String cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
        pb.inheritIO();
        Process process = pb.start();
        process.waitFor();
    }
}
