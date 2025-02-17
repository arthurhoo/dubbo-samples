/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dubbo.scenario.builder;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Multi-version matcher
 */
public class VersionMatcher {

    private static final Logger logger = LoggerFactory.getLogger(VersionMatcher.class);
    public static final String CASE_VERSIONS_FILE = "caseVersionsFile";
    public static final String CANDIDATE_VERSIONS = "candidateVersions";
    public static final String OUTPUT_FILE = "outputFile";
    public static final String INCLUDE_CASE_SPECIFIC_VERSION = "includeCaseSpecificVersion";

    public static void main(String[] args) throws Exception {

        String caseVersionsFile = System.getProperty(CASE_VERSIONS_FILE);
        String candidateVersionListStr = System.getProperty(CANDIDATE_VERSIONS);
        String outputFile = System.getProperty(OUTPUT_FILE);
        // whether include specific version which defined in case-versions.conf
        // specific version: a real version not contains wildcard '*'
        boolean includeCaseSpecificVersion = Boolean.parseBoolean(System.getProperty(INCLUDE_CASE_SPECIFIC_VERSION, "true"));

        if (StringUtils.isBlank(candidateVersionListStr)) {
            errorAndExit(Constants.EXIT_FAILED, "Missing system prop: '{}'", CANDIDATE_VERSIONS);
        }
        if (StringUtils.isBlank(caseVersionsFile)) {
            errorAndExit(Constants.EXIT_FAILED, "Missing system prop: '{}'", CASE_VERSIONS_FILE);
        }
        File file = new File(caseVersionsFile);
        if (!file.exists() || !file.isFile()) {
            errorAndExit(Constants.EXIT_FAILED, "File not exists or isn't a file: {}", file.getAbsolutePath());
        }
        if (StringUtils.isBlank(outputFile)) {
            errorAndExit(Constants.EXIT_FAILED, "Missing system prop: '{}'", OUTPUT_FILE);
        }
        new File(outputFile).getParentFile().mkdirs();

        VersionMatcher versionMatcher = new VersionMatcher();
        versionMatcher.doMatch(caseVersionsFile, candidateVersionListStr, outputFile, includeCaseSpecificVersion);
    }

    private void doMatch(String caseVersionsFile, String candidateVersionListStr, String outputFile, boolean includeCaseSpecificVersion) throws Exception {
        logger.info("{}: {}", CANDIDATE_VERSIONS, candidateVersionListStr);
        logger.info("{}: {}", CASE_VERSIONS_FILE, caseVersionsFile);
        logger.info("{}: {}", OUTPUT_FILE, outputFile);

        // parse and expand to versions list
        Map<String, List<String>> candidateVersionMap = parseVersionList(candidateVersionListStr);

        // parse case version match rules
        Map<String, List<MatchRule>> caseVersionMatchRules = parseCaseVersionMatchRules(caseVersionsFile);

        Map<String, List<String>> matchedVersionMap = new LinkedHashMap<>();

        candidateVersionMap.forEach((component, candidateVersionList) -> {
            List<MatchRule> matchRules = caseVersionMatchRules.get(component);
            if (matchRules == null || matchRules.isEmpty()) {
                return;
            }

            // matching rules
            List<String> matchedVersionList = new ArrayList<>();
            for (String version : candidateVersionList) {
                if (hasIncludeVersion(matchRules, version)) {
                    matchedVersionList.add(version);
                }
            }

            //add case specific version
            if (matchedVersionList.isEmpty() && includeCaseSpecificVersion) {
                for (MatchRule matchRule : matchRules) {
                    if (!matchRule.isExcluded() && matchRule instanceof PlainMatchRule) {
                        matchedVersionList.add(((PlainMatchRule) matchRule).getVersion());
                    }
                }
            }
            if (matchedVersionList.size() > 0) {
                matchedVersionMap.put(component, matchedVersionList);
            }
        });

        // check if all component has matched version
        if (caseVersionMatchRules.size() != matchedVersionMap.size()) {
            List<String> components = new ArrayList<>(caseVersionMatchRules.keySet());
            components.removeAll(matchedVersionMap.keySet());
            for (String component : components) {
                errorAndExit(Constants.EXIT_UNMATCHED, "Component not match: {}, rules: {}", component, caseVersionMatchRules.get(component));
            }
        }

        List<List<String>> versionProfiles = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : matchedVersionMap.entrySet()) {
            String component = entry.getKey();
            List<String> versions = entry.getValue();
            versionProfiles = appendComponent(versionProfiles, component, versions);
        }

        if (versionProfiles.isEmpty()) {
            errorAndExit(Constants.EXIT_UNMATCHED, "Version matrix is empty");
        }
        try (FileOutputStream fos = new FileOutputStream(outputFile);
             PrintWriter pw = new PrintWriter(fos)) {
            StringBuilder sb = new StringBuilder();
            int size = versionProfiles.size();
            for (int i = 0; i < size; i++) {
                List<String> profile = versionProfiles.get(i);
                for (String version : profile) {
                    //-Dxxx.version=1.0.0
                    sb.append("-D").append(version.replace(':', '=')).append(" ");
                }
                sb.append("\n");
            }
            pw.print(sb);
            logger.info("Version matrix total: {}, list: \n{}", versionProfiles.size(), sb);
        } catch (IOException e) {
            errorAndExit(Constants.EXIT_FAILED, "Write version matrix failed: " + e.toString(), e);
        }
    }

    private static boolean hasIncludeVersion(List<MatchRule> matchRules, String version) {
        boolean included = false;
        version = trimVersion(version);
        for (MatchRule matchRule : matchRules) {
            if (matchRule.match(version)) {
                // excluded rule has higher priority than included rule
                if (matchRule.isExcluded()) {
                    return false;
                } else {
                    included = true;
                }
            }
        }
        return included;
    }

    private static Pattern getWildcardPattern(String versionPattern) {
        // convert 'version_prefix*' to regex
        String regex = "\\Q" + versionPattern.replace("*", "\\E.*?\\Q") + "\\E";
        return Pattern.compile(regex);
    }

    private static List<List<String>> appendComponent(List<List<String>> versionProfiles, String component, List<String> versions) {
        List<List<String>> newProfiles = new ArrayList<>();
        for (String version : versions) {
            String versionProfile = createVersionProfile(component, version);
            if (versionProfiles.isEmpty()) {
                List<String> newProfile = new ArrayList<>();
                newProfile.add(versionProfile);
                newProfiles.add(newProfile);
            } else {
                //extends version matrix
                for (int i = 0; i < versionProfiles.size(); i++) {
                    List<String> profile = versionProfiles.get(i);
                    List<String> newProfile = new ArrayList<>(profile);
                    newProfile.add(versionProfile);
                    newProfiles.add(newProfile);
                }
            }
        }
        return newProfiles;
    }

    private static String createVersionProfile(String component, String version) {
        return component + ":" + version;
    }

    private Map<String, List<MatchRule>> parseCaseVersionMatchRules(String caseVersionsFile) throws Exception {
        // Possible formats:
        //dubbo.version=2.7*, 3.*, !2.7.8*, !2.7.8.1
        //dubbo.version=<=2.7.7, >2.7.8, >=3.0
        //dubbo.version=[<=2.7.7, >2.7.8, >=3.0]
        //dubbo.version=["<=2.7.7", ">2.7.8", ">=3.0"]
        //dubbo.version=['<=2.7.7', '>2.7.8', '>=3.0']

        try {
            Map<String, List<MatchRule>> ruleMap = new LinkedHashMap<>();
            String content = FileUtil.readFully(caseVersionsFile);
            BufferedReader br = new BufferedReader(new StringReader(content));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("#") || StringUtils.isBlank(line)) {
                    continue;
                }
                int p = line.indexOf('=');
                String component = line.substring(0, p);
                String patternStr = line.substring(p + 1);
                patternStr = trimRule(patternStr, "[", "]");
                String[] patterns = patternStr.split(",");
                List<MatchRule> ruleList = new ArrayList<>();
                for (String pattern : patterns) {
                    pattern = trimRule(pattern, "\"");
                    pattern = trimRule(pattern, "'");
                    if (pattern.startsWith(">") || pattern.startsWith("<")) {
                        ruleList.add(parseRangeMatchRule(pattern));
                    } else {
                        boolean excluded = false;
                        if (pattern.startsWith("!")) {
                            excluded = true;
                            pattern = pattern.substring(1).trim();
                        }
                        if (pattern.contains("*")) {
                            ruleList.add(new WildcardMatchRule(excluded, pattern));
                        } else {
                            ruleList.add(new PlainMatchRule(excluded, pattern));
                        }
                    }
                }
                ruleMap.put(component, ruleList);
            }
            return ruleMap;
        } catch (Exception e) {
            logger.error("Parse case versions rules failed: {}", caseVersionsFile, e);
            throw e;
        }
    }

    private String trimRule(String rule, String ch) {
        return trimRule(rule, ch, ch);
    }

    private String trimRule(String rule, String begin, String end) {
        rule = rule.trim();
        if (rule.startsWith(begin)) {
            if (rule.endsWith(end)){
                return rule.substring(1, rule.length()-1);
            } else {
                throw new IllegalArgumentException("Version match rule is invalid: "+rule);
            }
        }
        return rule;
    }

    private Map<String, List<String>> parseVersionList(String versionListStr) {
        // "<component1>:<version1>[,version2];<component2>:<version1>[,version2]"
        // "<component1>:<version1>[;component1:<version2];<component2>:<version1>[;component2:version2];"

        Map<String, List<String>> versionMap = new LinkedHashMap<>();
        //split components by ';' or '\n'
        String[] compvers = versionListStr.split("[;\n]");
        for (String compver : compvers) {
            if (StringUtils.isBlank(compver)) {
                continue;
            }
            String[] strs = compver.split(":");
            String component = strs[0].trim();
            String[] vers = strs[1].split(",");
            List<String> versionList = versionMap.computeIfAbsent(component, (key) -> new ArrayList<>());
            for (String ver : vers) {
                versionList.add(ver.trim());
            }
        }
        return versionMap;
    }

    private static void errorAndExit(int exitCode, String format, Object... arguments) {
        //insert ERROR_MSG_FLAG before error msg
        Object[] newArgs = new Object[arguments.length + 1];
        newArgs[0] = Constants.ERROR_MSG_FLAG;
        System.arraycopy(arguments, 0, newArgs, 1, arguments.length);
        logger.error("{} " + format, newArgs);
        System.exit(exitCode);
    }

    private interface MatchRule {

        boolean isExcluded();

        boolean match(String version);

    }

    private static abstract class ExcludableMatchRule implements MatchRule {
        boolean excluded;

        public ExcludableMatchRule(boolean excluded) {
            this.excluded = excluded;
        }

        public boolean isExcluded() {
            return excluded;
        }

    }

    private static class PlainMatchRule extends ExcludableMatchRule {
        private String version;

        public PlainMatchRule(boolean excluded, String version) {
            super(excluded);
            this.version = version;
        }

        @Override
        public boolean match(String version) {
            return this.version.equals(version);
        }

        public String getVersion() {
            return version;
        }

        @Override
        public String toString() {
            return (excluded?"!":"")+version;
        }
    }

    private static class WildcardMatchRule extends ExcludableMatchRule {
        private Pattern versionPattern;
        private String versionWildcard;

        public WildcardMatchRule(boolean excluded, String versionWildcard) {
            super(excluded);
            this.versionPattern = getWildcardPattern(versionWildcard);
            this.versionWildcard = versionWildcard;
        }

        @Override
        public boolean match(String version) {
            return this.versionPattern.matcher(version).matches();
        }
        @Override
        public String toString() {
            return (excluded?"!":"")+versionWildcard;
        }
    }

    private static class RangeMatchRule implements MatchRule {
        private VersionComparator comparator;
        private String operator;
        private String version;
        private int[] versionInts;

        public RangeMatchRule(String operator, String version) {
            this.operator = operator;
            this.version = version;
            this.comparator = getVersionComparator(operator);
            this.versionInts = toVersionInts(version);
        }

        @Override
        public boolean isExcluded() {
            return false;
        }

        @Override
        public boolean match(String matchingVersion) {
            int[] matchingVersionInts = toVersionInts(matchingVersion);
            return comparator.match(matchingVersionInts, versionInts);
        }

        @Override
        public String toString() {
            return operator+version;
        }
    }

    private static class CombineMatchRule implements MatchRule {
        List<MatchRule> matchRules = new ArrayList<>();

        public CombineMatchRule(List<MatchRule> matchRules) {
            this.matchRules.addAll(matchRules);
        }

        @Override
        public boolean isExcluded() {
            return false;
        }

        @Override
        public boolean match(String version) {
            for (MatchRule matchRule : matchRules) {
                if (!matchRule.match(version)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            for (MatchRule rule : matchRules) {
                sb.append(rule.toString()).append(' ');
            }
            return sb.toString();
        }
    }

    private static int[] toVersionInts(String version) {
        String[] vers = StringUtils.split(version, '.');
        int[] ints = new int[4];
        for (int i = 0; i < ints.length; i++) {
            if (vers.length > i) {
                ints[i] = Integer.parseInt(vers[i]);
            } else {
                break;
            }
        }
        return ints;
    }

    private static String trimVersion(String version) {
        //remove '-SNAPSHOT'
        int p = version.indexOf('-');
        if (p > 0) {
            version = version.substring(0, p);
        }
        return version;
    }

    private static VersionComparator getVersionComparator(String operator) {
        if (operator.startsWith(">=")) {
            return greaterThanOrEqualToComparator;
        } else if (operator.startsWith(">")) {
            return greaterThanComparator;
        } else if (operator.startsWith("<=")) {
            return lessThanOrEqualToComparator;
        } else if (operator.startsWith("<")) {
            return lessThanComparator;
        }
        throw new IllegalArgumentException("Comparison operator is invalid: "+operator);
    }

    private Pattern cmpExprPattern = Pattern.compile("<=|>=|<|>|[\\d\\.]+");
    private MatchRule parseRangeMatchRule(String versionPattern) {
        List<MatchRule> matchRules = new ArrayList<>();
        Matcher matcher = cmpExprPattern.matcher(versionPattern);
        while (matcher.find()) {
            if (matchRules.size() == 2) {
                throw new IllegalArgumentException("Invalid range match rule: "+versionPattern);
            }
            String operator = matcher.group();
            if(!matcher.find()){
                throw new IllegalArgumentException("Parse range match rule failed, unexpected EOF: "+versionPattern);
            }
            String version = matcher.group();
            matchRules.add(new RangeMatchRule(operator, version));
        }

        if (matchRules.size() == 1) {
            return matchRules.get(0);
        } else if (matchRules.size() == 2) {
            return new CombineMatchRule(matchRules);
        }
        throw new IllegalArgumentException("Parse range match rule failed: "+versionPattern);
    }

    private interface VersionComparator {
        boolean match(int[] matchingVersionInts, int[] versionInts);
    }

    private static VersionComparator greaterThanComparator = new VersionComparator() {
        @Override
        public boolean match(int[] matchingVersionInts, int[] versionInts) {
            for (int i = 0; i < versionInts.length; i++) {
                if (matchingVersionInts[i] > versionInts[i]) {
                    return true;
                } else if (matchingVersionInts[i] < versionInts[i]) {
                    return false;
                }
            }
            return false;
        }
    };

    private static VersionComparator greaterThanOrEqualToComparator = new VersionComparator() {
        @Override
        public boolean match(int[] matchingVersionInts, int[] versionInts) {
            return Arrays.equals(matchingVersionInts, versionInts) ||
                    greaterThanComparator.match(matchingVersionInts, versionInts);
        }
    };

    private static VersionComparator lessThanComparator = new VersionComparator() {
        @Override
        public boolean match(int[] matchingVersionInts, int[] versionInts) {
            for (int i = 0; i < versionInts.length; i++) {
                if (matchingVersionInts[i] < versionInts[i]) {
                    return true;
                } else if (matchingVersionInts[i] > versionInts[i]) {
                    return false;
                }
            }
            return false;
        }
    };

    private static VersionComparator lessThanOrEqualToComparator = new VersionComparator() {
        @Override
        public boolean match(int[] matchingVersionInts, int[] versionInts) {
            return Arrays.equals(matchingVersionInts, versionInts) ||
                    lessThanComparator.match(matchingVersionInts, versionInts);
        }
    };

}
