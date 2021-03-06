package com.holmsted.gerrit;

import com.google.common.base.Strings;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nonnull;

import com.holmsted.json.JsonUtils;

public class GerritStatReader {

    public static final int GERRIT_DEFAULT_PORT = 29418;
    public static final int NO_COMMIT_LIMIT = -1;

    private final String serverName;
    private final int port;
    private List<String> projectNames = new ArrayList<>();
    private int perQueryCommitLimit = NO_COMMIT_LIMIT;
    private int overallCommitLimit = NO_COMMIT_LIMIT;

    public static GerritStatReader fromCommandLine(String serverName, int serverPort) {
        if (serverPort != 0) {
            return new GerritStatReader(serverName, serverPort);
        } else {
            return new GerritStatReader(serverName);
        }
    }

    class GerritOutput {
        private String output;
        private int rowCount;
        private int runtimeMsec;
        private boolean moreChanges;
        private int lastLineStartIndex = -1;

        public GerritOutput(@Nonnull String output) {
            this.output = output;
            int lastLineBreak = output.lastIndexOf('\n');
            if (lastLineBreak != -1) {
                lastLineStartIndex = output.lastIndexOf('\n', lastLineBreak - 1);
                if (lastLineStartIndex != -1) {
                    JSONObject metadata = JsonUtils.readJsonString(this.output.substring(lastLineStartIndex));
                    moreChanges = metadata.optBoolean("moreChanges");
                    rowCount = metadata.optInt("rowCount");
                    runtimeMsec = metadata.optInt("runTimeMilliseconds");
                }
            }
        }

        public boolean hasMoreChanges() {
            return moreChanges;
        }

        public int getRowCount() {
            return rowCount;
        }

        public int getRuntimeMec() {
            return runtimeMsec;
        }

        @Override
        public String toString() {
            return (lastLineStartIndex != -1) ? output.substring(0, lastLineStartIndex) : output;
        }
    }

    class GerritDataReader {
        private int startOffset;

        public void setStartOffset(int startOffset) {
            this.startOffset = startOffset;
        }

        public GerritOutput readData() {
            Runtime runtime = Runtime.getRuntime();
            try {
                String projectNameList = createProjectNameList();
                String command = String.format("ssh -p %s %s gerrit query %s "
                                + "--format=JSON "
                                + "--all-approvals "
                                + "--all-reviewers "
                                + "--comments "
                                + createStartOffsetArg()
                                + createLimitArg(),
                        String.valueOf(port), serverName, projectNameList);
                System.out.println(command);

                Process exec = runtime.exec(command, null);

                char[] buffer = new char[1024];
                int readChars;

                BufferedReader readerOut = new BufferedReader(new InputStreamReader(exec.getInputStream()));
                StringBuilder output = new StringBuilder();
                while ((readChars = readerOut.read(buffer)) != -1) {
                    output.append(String.copyValueOf(buffer, 0, readChars));
                }
                readerOut.close();

                BufferedReader readerErr = new BufferedReader(new InputStreamReader(exec.getErrorStream()));
                StringBuilder error = new StringBuilder();
                while ((readChars = readerErr.read(buffer)) != -1) {
                    error.append(String.copyValueOf(buffer, 0, readChars));
                }
                readerErr.close();

                int errorCode = exec.waitFor();
                if (errorCode != 0) {
                    System.err.println("Process exited with return code " + errorCode + " and output:");
                    System.err.println(error);
                    return null;
                }

                return new GerritOutput(Strings.nullToEmpty(output.toString()));
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }

            return null;
        }

        private String createStartOffsetArg() {
            return startOffset != 0 ? "--start " + String.valueOf(startOffset) + " " : "";
        }
    }

    public GerritStatReader(String serverName) {
        this.serverName = serverName;
        port = GERRIT_DEFAULT_PORT;
    }

    public GerritStatReader(String serverName, int port) {
        this.serverName = serverName;
        this.port = port;
    }

    public void setProjectName(String projectName) {
        projectNames.clear();
        projectNames.add(projectName);
    }

    /**
     * Fetch only x commits per query.
     * Server maximum is respected; it's typically 500 or something similar.
     */
    public void setPerQueryCommitLimit(int limit) {
        perQueryCommitLimit = limit;
    }

    /**
     * Sets how many commits' stats are downloaded. If this number exceeds the server limit,
     * multiple requests will be made to fulfill the goal.
     * <p> TODO this does not get respected if the limit is not a multiple of the server limit.
     */
    public void setCommitLimit(int overallLimit) {
        overallCommitLimit = overallLimit;
    }

    public void setProjectNames(List<String> projectNames) {
        this.projectNames = projectNames;
    }

    /**
     * Reads the data in json format from gerrit.
     */
    public String readData() {
        if (overallCommitLimit != NO_COMMIT_LIMIT) {
            System.out.println("Reading data from " + serverName + " for last " + overallCommitLimit + " commits");
        } else {
            System.out.println("Reading all commit data from " + serverName);
        }

        GerritDataReader connection = new GerritDataReader();
        StringBuilder builder = new StringBuilder();

        boolean hasMoreChanges = true;
        int offset = 0;
        while (hasMoreChanges && (offset < overallCommitLimit || overallCommitLimit == NO_COMMIT_LIMIT)) {
            connection.setStartOffset(offset);
            GerritOutput gerritOutput = connection.readData();
            if (gerritOutput == null) {
                break;
            }
            builder.append(gerritOutput.toString());
            hasMoreChanges = gerritOutput.hasMoreChanges();
            offset += gerritOutput.getRowCount();
        }

        return builder.toString();
    }

    private String createLimitArg() {
        return perQueryCommitLimit != NO_COMMIT_LIMIT ? "limit:" + String.valueOf(perQueryCommitLimit) : "";
    }

    private String createProjectNameList() {
        StringBuilder builder = new StringBuilder("project:^");
        if (projectNames.isEmpty()) {
            builder.append(".*");
        } else {
            for (String projectName : projectNames) {
                builder.append(projectName).append("|");
            }
            builder.setLength(builder.length() - 1);
        }
        return builder.toString();
    }
}
