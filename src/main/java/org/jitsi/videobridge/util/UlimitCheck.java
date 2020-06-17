package org.jitsi.videobridge.util;


import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import org.jitsi.utils.logging.Logger;

public class UlimitCheck {
    private static final Logger logger = Logger.getLogger(UlimitCheck.class);

    public static String getOutputFromCommand(String command) {
        ProcessBuilder pb = new ProcessBuilder(new String[] { "bash", "-c", command });
        pb.redirectErrorStream(true);
        try {
            Process p = pb.start();
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            return br.lines().reduce(String::concat).orElse("null?");
        } catch (IOException e) {
            return null;
        }
    }

    public static Integer getIntFromCommand(String command) {
        try {
            return Integer.valueOf(Integer.parseInt(getOutputFromCommand(command)));
        } catch (NumberFormatException n) {
            return null;
        }
    }

    public static void printUlimits() {
        Integer fileLimit = getIntFromCommand("ulimit -n");
        Integer fileLimitHard = getIntFromCommand("ulimit -Hn");
        Integer threadLimit = getIntFromCommand("ulimit -u");
        Integer threadLimitHard = getIntFromCommand("ulimit -Hu");
        StringBuilder sb = (new StringBuilder("Running with open files limit ")).append(fileLimit).append(" (hard ").append(fileLimitHard).append(')').append(", thread limit ").append(threadLimit).append(" (hard ").append(threadLimitHard).append(").");
        boolean warn = (fileLimit == null || fileLimit.intValue() <= 4096 || threadLimit == null || threadLimit.intValue() <= 8192);
        if (warn) {
            sb.append(" These values are too low and they will limit the ")
                    .append("number of participants that the bridge can serve ")
                    .append("simultaneously.");
            logger.warn(sb);
        } else {
            logger.info(sb);
        }
    }
}
