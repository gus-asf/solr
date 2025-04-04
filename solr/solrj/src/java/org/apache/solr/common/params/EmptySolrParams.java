/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.common.params;

import java.util.Iterator;
import java.util.Map.Entry;

/** Empty, immutable, SolrParams. */
class EmptySolrParams extends SolrParams {

  static final SolrParams INSTANCE = new EmptySolrParams();

  @SuppressWarnings("rawtypes")
  private static final Iterator EMPTY_ITERATOR =
      new Iterator() {
        @Override
        public boolean hasNext() {
          return false;
        }

        @Override
        public Object next() {
          throw new IllegalStateException("No elements available in iterator");
        }
      };

  @Override
  public String get(String param) {
    return null;
  }

  @Override
  public String[] getParams(String param) {
    return null;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Iterator<String> getParameterNamesIterator() {
    return EMPTY_ITERATOR;
  }

  @SuppressWarnings("unchecked")
  @Override
  public Iterator<Entry<String, String[]>> iterator() {
    return EMPTY_ITERATOR;
  }
}
