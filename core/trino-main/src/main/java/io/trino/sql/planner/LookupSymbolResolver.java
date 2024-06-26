/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.sql.planner;

import com.google.common.collect.ImmutableMap;
import io.trino.spi.connector.ColumnHandle;
import io.trino.spi.predicate.NullableValue;
import io.trino.sql.ir.Constant;

import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public class LookupSymbolResolver
        implements SymbolResolver
{
    private final Map<Symbol, ColumnHandle> assignments;
    private final Map<ColumnHandle, NullableValue> bindings;

    public LookupSymbolResolver(Map<Symbol, ColumnHandle> assignments, Map<ColumnHandle, NullableValue> bindings)
    {
        requireNonNull(assignments, "assignments is null");
        requireNonNull(bindings, "bindings is null");

        this.assignments = ImmutableMap.copyOf(assignments);
        this.bindings = ImmutableMap.copyOf(bindings);
    }

    @Override
    public Optional<Constant> getValue(Symbol symbol)
    {
        ColumnHandle column = assignments.get(symbol);

        if (column == null || !bindings.containsKey(column)) {
            return Optional.empty();
        }

        return Optional.of(new Constant(symbol.getType(), bindings.get(column).getValue()));
    }
}
