/**
 *
 *    Copyright 2020, Optimizely and contributors
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.optimizely.ab.config.audience.match;

import java.util.ArrayList;
import java.util.List;

import static com.optimizely.ab.internal.AttributesUtil.parseNumeric;
import static com.optimizely.ab.internal.AttributesUtil.stringIsNullOrEmpty;

public final class SemanticVersion {

    private static final char BUILD_SEPERATOR = '+';
    private static final char PRE_RELEASE_SEPERATOR = '-';

    private final String version;

    public SemanticVersion(String version) {
        this.version = version;
    }

    public int compare(SemanticVersion targetedVersion) throws Exception {

        if (targetedVersion == null || stringIsNullOrEmpty(targetedVersion.version)) {
            return 0;
        }

        SemanticVersionObj targetedVersionParts = targetedVersion.splitSemanticVersion();
        SemanticVersionObj userVersionParts = splitSemanticVersion();

        for (int index = 0; index < targetedVersionParts.versionParts.size(); index++) {

            if (userVersionParts.versionParts.size() <= index) {
                return -1;
            }
            Integer targetVersionPartInt = targetedVersionParts.versionParts.get(index);
            Integer userVersionPartInt = userVersionParts.versionParts.get(index);

            if (!userVersionPartInt.equals(targetVersionPartInt)) {
                return userVersionPartInt < targetVersionPartInt ? -1 : 1;
            }
        }
        if (targetedVersionParts.preRelease != null &&
            userVersionParts.preRelease == null) {
            return 1;
        } else if (targetedVersionParts.preRelease != null) {
            for (int index = 0; index < targetedVersionParts.preRelease.size(); index++) {
                // Compare strings
                int result = userVersionParts.preRelease.get(index).compareTo(targetedVersionParts.preRelease.get(index));
                if (result != 0) {
                    return result;
                }
            }
        }

        return 0;
    }

    private List<String> preParts, metaParts;

    private boolean stateRelease(char[] input, int index) throws Exception {
        int pos = index;
        while ((pos < input.length)
            && ((input[pos] >= '0' && input[pos] <= '9')
            || (input[pos] >= 'a' && input[pos] <= 'z')
            || (input[pos] >= 'A' && input[pos] <= 'Z') || input[pos] == PRE_RELEASE_SEPERATOR)) {
            pos++; // match [0..9a-zA-Z-]+
        }
        if (pos == index) { // Empty String -> Error
            return false;
        }
        if (input[index] == '0') { // Leading zero
            // throw error
            throw new Exception("Invalid Semantic Version.");
        }

        preParts.add(new String(input, index, pos - index));
        if (pos == input.length) { // End of input
            return true;
        }
        if (input[pos] == '.') { // More parts -> descend
            return stateRelease(input, pos + 1);
        }
        if (input[pos] == BUILD_SEPERATOR) { // Build meta -> descend
            metaParts = new ArrayList<>();
            return stateMeta(input, pos + 1);
        }

        return false;
    }

    private boolean stateMeta(char[] input, int index) {
        int pos = index;
        while ((pos < input.length)
            && ((input[pos] >= '0' && input[pos] <= '9')
            || (input[pos] >= 'a' && input[pos] <= 'z')
            || (input[pos] >= 'A' && input[pos] <= 'Z') || input[pos] == PRE_RELEASE_SEPERATOR)) {
            pos++; // match [0..9a-zA-Z-]+
        }
        if (pos == index) { // Empty String -> Error
            return false;
        }

        metaParts.add(new String(input, index, pos - index));
        if (pos == input.length) { // End of input
            return true;
        }
        if (input[pos] == '.') { // More parts -> descend
            return stateMeta(input, pos + 1);
        }

        return false;
    }

    public SemanticVersionObj splitSemanticVersion() throws Exception {
        // Contains white spaces
        if (version.contains(" ")) {   // log and throw error
            throw new Exception("Semantic version contains white spaces. Invalid Semantic Version.");
        }

        List<Integer> versionParts = new ArrayList<>();

        char[] input = version.toCharArray();

        int pos = 0;
        // major.minor.patch. Example: 1.2.3
        for (int verIndex = 0; verIndex < 3; verIndex++) {
            int index = pos;
            while (pos < input.length && input[pos] >= '0' && input[pos] <= '9') {
                pos++; // match [0..9]+
            }

            int verSize = pos - index; // Size of version part
            if (verSize == 0 || (input[index] == '0' && verSize > 1)) { // Empty String or Leading zero -> Error
                throw new Exception("Invalid Semantic Version.");
            }

            versionParts.add(parseNumeric(new String(input, index, verSize)));

            if (input.length > pos && input[pos] == '.') {
                pos++;
            } else {
                break;
            }
        }

        if (input.length > pos) {
            if (input[pos] == BUILD_SEPERATOR) { // We have build meta tags -> descend
                metaParts = new ArrayList<>();
                if (versionParts.size() < 3 || !stateMeta(input, pos + 1)) {
                    // throw error
                    throw new Exception("Invalid Semantic Version.");
                }
            } else if (input[pos] == PRE_RELEASE_SEPERATOR) { // We have pre release tags -> descend
                preParts = new ArrayList<>();
                if (versionParts.size() < 3 || !stateRelease(input, pos + 1)) {
                    // throw error
                    throw new Exception("Invalid Semantic Version.");
                }
            } else {
                throw new Exception("Invalid Semantic Version.");
            }
        }

        return new SemanticVersionObj(versionParts, preParts, metaParts);
    }


    private static class SemanticVersionObj {

        private final List<Integer> versionParts;

        /**
         * Pre-release tags (potentially empty, but never null). This is private to
         * ensure read only access.
         */
        private final List<String> preRelease;

        /**
         * Build meta data tags (potentially empty, but never null). This is private
         * to ensure read only access.
         */
        private final List<String> buildMeta;

        public SemanticVersionObj(List<Integer> versionParts, List<String> preRelease, List<String> buildMeta) {
            this.versionParts = versionParts;
            this.preRelease = preRelease;
            this.buildMeta = buildMeta;
        }
    }
}
