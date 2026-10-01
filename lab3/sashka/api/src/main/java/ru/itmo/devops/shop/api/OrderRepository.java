package ru.itmo.devops.shop.api;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {
    private static final RowMapper<Order> ORDER_MAPPER = OrderRepository::mapOrder;

    private final JdbcTemplate jdbcTemplate;
    private final SimpleJdbcInsert insertOrder;

    public OrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.insertOrder = new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("orders")
                .usingColumns("item", "quantity")
                .usingGeneratedKeyColumns("id");
    }

    public Order create(OrderRequest request) {
        var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("item", request.item(), Types.VARCHAR)
                .addValue("quantity", request.quantity(), Types.INTEGER);
        long id = insertOrder.executeAndReturnKey(parameters).longValue();
        return findById(id);
    }

    public List<Order> findAll() {
        return jdbcTemplate.query("""
                SELECT id, item, quantity, status, created_at, processed_at
                FROM orders
                ORDER BY id
                """, ORDER_MAPPER);
    }

    private Order findById(long id) {
        return jdbcTemplate.queryForObject("""
                SELECT id, item, quantity, status, created_at, processed_at
                FROM orders
                WHERE id = ?
                """, ORDER_MAPPER, id);
    }

    private static Order mapOrder(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Order(
                resultSet.getLong("id"),
                resultSet.getString("item"),
                resultSet.getInt("quantity"),
                resultSet.getString("status"),
                resultSet.getObject("created_at", OffsetDateTime.class),
                resultSet.getObject("processed_at", OffsetDateTime.class)
        );
    }
}
