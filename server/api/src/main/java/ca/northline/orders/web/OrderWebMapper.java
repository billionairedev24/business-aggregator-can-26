package ca.northline.orders.web;

import ca.northline.orders.application.OrderViews.Counts;
import ca.northline.orders.application.OrderViews.Line;
import ca.northline.orders.application.OrderViews.OrderBoard;
import ca.northline.orders.application.OrderViews.OrderSummary;
import ca.northline.orders.web.OrderResponses.BoardResponse;
import ca.northline.orders.web.OrderResponses.CountsResponse;
import ca.northline.orders.web.OrderResponses.LineResponse;
import ca.northline.orders.web.OrderResponses.OrderResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
interface OrderWebMapper {

    @Mapping(target = "items", source = "orders")
    BoardResponse toResponse(OrderBoard board);

    OrderResponse toResponse(OrderSummary order);

    LineResponse toResponse(Line line);

    CountsResponse toResponse(Counts counts);
}
