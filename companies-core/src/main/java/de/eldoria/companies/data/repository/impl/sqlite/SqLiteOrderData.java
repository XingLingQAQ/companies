/*
 *     SPDX-License-Identifier: AGPL-3.0-only
 *
 *     Copyright (C EldoriaRPG Team and Contributor
 */
package de.eldoria.companies.data.repository.impl.sqlite;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.chojo.sadu.wrapper.util.Row;
import de.eldoria.companies.commands.company.order.search.SearchQuery;
import de.eldoria.companies.components.order.OrderState;
import de.eldoria.companies.data.repository.impl.mariadb.MariaDbOrderData;
import de.eldoria.companies.data.wrapper.company.SimpleCompany;
import de.eldoria.companies.data.wrapper.order.FullOrder;
import de.eldoria.companies.data.wrapper.order.SimpleOrder;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;
import org.intellij.lang.annotations.Language;
import static de.eldoria.companies.data.StaticQueryAdapter.builder;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

public class SqLiteOrderData extends MariaDbOrderData {

    /**
     * Create a new QueryBuilderFactory
     *
     * @param executorService executor for futures
     * @param mapper
     */
    public SqLiteOrderData(ExecutorService executorService, ObjectMapper mapper) {
        super(executorService, mapper);
    }

    @Override
    protected List<SimpleOrder> getExpiredOrders(int hours) {
        @Language("sqlite")
        var query = """
                SELECT o.id,
                       last_update,
                       company,
                       state,
                       owner_uuid,
                       name,
                       created
                FROM order_states s
                         LEFT JOIN orders o
                                   ON o.id = s.id
                WHERE last_update < datetime(CURRENT_TIMESTAMP, '-' || ? || ' HOUR')
                  AND company IS NOT NULL
                  AND state = ?
                ORDER BY last_update""";
        return builder(SimpleOrder.class)
                .query(query)
                .parameter(stmt -> stmt.setInt(hours)
                                       .setInt(OrderState.CLAIMED.stateId()))
                .readRow(this::buildSimpleOrder)
                .allSync();
    }

    @Override
    protected List<SimpleOrder> getExpiredOrdersByCompany(int hours, SimpleCompany company) {
        @Language("sqlite")
        var query = """
                SELECT o.id,
                       last_update,
                       company,
                       state,
                       owner_uuid,
                       name,
                       created
                FROM order_states s
                         LEFT JOIN orders o
                                   ON o.id = s.id
                WHERE last_update < datetime(CURRENT_TIMESTAMP, '-' || ? || ' HOUR')
                  AND company = ?
                  AND state = ?
                ORDER BY last_update
                """;
        return builder(SimpleOrder.class)
                .query(query)
                .parameter(stmt -> stmt.setInt(hours)
                                       .setInt(company.id())
                                       .setInt(OrderState.CLAIMED.stateId()))
                .readRow(this::buildSimpleOrder)
                .allSync();
    }

    @Override
    protected void deliver(OfflinePlayer player, SimpleOrder order, Material material, int amount) {
        @Language("sqlite")
        var query = """
                INSERT
                INTO orders_delivered(id, worker_uuid, material, delivered)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(id, worker_uuid, material) DO UPDATE SET delivered = delivered + excluded.delivered""";
        builder()
                .query(query)
                .parameter(stmt -> stmt.setInt(order.id())
                                       .setUuidAsBytes(player.getUniqueId())
                                       .setString(material.name())
                                       .setInt(amount))
                .update()
                .sendSync();
    }

    @Override
    protected List<FullOrder> getOrdersByQuery(SearchQuery searchQuery, OrderState min, OrderState max) {
        List<List<Integer>> results = new ArrayList<>();
        Set<Integer> materialMatch;
        var materialFilter = !searchQuery.materials()
                                         .isEmpty();
        // This is pain. SqLite doesn't support regex from stock, so we need to do some dirty looping.
        if (materialFilter) {
            for (var material : searchQuery.materials()) {
                @Language("sqlite")
                var select = """
                        SELECT DISTINCT c.id
                        FROM order_content c
                                 LEFT JOIN order_states s
                                           ON s.id = c.id
                        WHERE material LIKE ?
                          AND s.state >= ?
                          AND s.state <= ?""";
                var ids = builder(Integer.class)
                        .query(select)
                        .parameter(stmt -> stmt.setString(searchQuery.isExactMatch() ? material : "%" + material + "%"))
                        .readRow(rs -> rs.getInt(1))
                        .allSync();
                results.add(ids);
            }
            if (searchQuery.isAnyMaterial()) {
                materialMatch = results.stream()
                                       .flatMap(List::stream)
                                       .collect(Collectors.toSet());
            } else {
                var first = results.remove(0);
                if (!results.isEmpty()) {
                    for (var result : results) {
                        first.retainAll(result);
                    }
                }
                materialMatch = new HashSet<>(first);
            }
        } else {
            materialMatch = new HashSet<>();
        }

        @Language("sqlite")
        var query = """
                SELECT o.id, o.owner_uuid, o.name, o.created, os.company, os.last_update, os.state
                FROM orders o
                         LEFT JOIN (SELECT c.id, SUM(amount) AS amount, SUM(price) AS price
                                    FROM order_content c
                                    GROUP BY id) oc
                                   ON o.id = oc.id
                         LEFT JOIN order_states os
                                   ON o.id = os.id
                WHERE o.name LIKE ?
                  AND oc.price >= ?
                  AND oc.price <= ?
                  AND oc.amount >= ?
                  AND oc.amount <= ?
                  AND os.state >= ?
                  AND os.state <= ?""";
        var orders = builder(SimpleOrder.class)
                .query(query)
                .parameter(stmt -> stmt.setString("%" + searchQuery.name() + "%")
                                       .setDouble(searchQuery.minPrice())
                                       .setDouble(searchQuery.maxPrice())
                                       .setInt(searchQuery.minOrderSize())
                                       .setInt(searchQuery.maxOrderSize())
                                       .setInt(min.stateId())
                                       .setInt(max.stateId()))
                .readRow(this::buildSimpleOrder)
                .allSync();
        orders = orders.stream()
                       .filter(order -> !materialFilter || materialMatch.contains(order.id()))
                       .collect(Collectors.toList());
        var fullOrders = toFullOrders(orders);
        searchQuery.sort(fullOrders);
        return fullOrders;
    }

    @Override
    public void refreshMaterialPrices() {
        @Language("sqlite")
        var query = """
                INSERT INTO material_price(material, avg_price, min_price, max_price)
                SELECT material, avg_price, min_price, max_price
                FROM (SELECT c.material,
                             AVG(c.price / c.amount) AS avg_price,
                             MIN(c.price / c.amount) AS min_price,
                             MAX(c.price / c.amount) AS max_price
                      FROM (SELECT ROW_NUMBER() OVER (PARTITION BY material ORDER BY last_update DESC) AS id,
                                   material,
                                   amount,
                                   price,
                                   last_update
                            FROM order_content c
                                     LEFT JOIN order_states s ON c.id = s.id
                            WHERE s.state >= 200) c
                      WHERE c.id < 100
                      GROUP BY c.material) avg
                WHERE TRUE
                ON CONFLICT(material) DO UPDATE SET avg_price = excluded.avg_price""";
        builder()
                .queryWithoutParams(query)
                .update()
                .sendSync();
    }

    @Override
    public SimpleOrder buildSimpleOrder(Row row) throws SQLException {
        return new SimpleOrder(row.getInt("id"), row.getUuidFromBytes("owner_uuid"),
                // Sqlite cant read its own timestamp as timestamp. We need to parse them
                row.getString("name"), SqLiteAdapter.getTimestamp(row, "created"),
                row.getInt("company"), SqLiteAdapter.getTimestamp(row, "last_update"),
                OrderState.byId(row.getInt("state")));
    }
}
