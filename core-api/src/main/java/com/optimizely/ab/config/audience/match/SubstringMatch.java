/**
 *
 *    Copyright 2018-2020, Optimizely and contributors
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

import javax.annotation.Nullable;

/**
 * SubstringMatch checks if the attribute value contains the condition value.
 * This assumes both the condition and attribute values are provided as Strings.
 */
class SubstringMatch implements Match {
    @Nullable
    public Boolean eval(Object conditionValue, Object attributeValue) throws UnexpectedValueTypeException {
        if (!(conditionValue instanceof String)) {
            throw new UnexpectedValueTypeException();
        }

        if (!(attributeValue instanceof String)) {
            return null;
        }

        try {
            return attributeValue.toString().contains(conditionValue.toString());
        } catch (Exception e) {
            return null;
        }
    }
}

