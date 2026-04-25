package com.kaknnea.pos.mapper;

import com.kaknnea.pos.domain.Product;
import com.kaknnea.pos.domain.ProductImage;
import com.kaknnea.pos.dto.ProductDtos;
import com.kaknnea.pos.dto.ProductDto;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

@Mapper(componentModel = "spring")
public interface ProductMapper {
    @Mappings({
        @Mapping(target = "parentProductId", source = "parentProductId"),
        @Mapping(target = "parentProductNameEn", source = "parentProductNameEn"),
        @Mapping(target = "resolvedPrice", source = "resolvedPrice")
    })
    ProductDtos.ProductResponse toResponse(Product product);

    @Mapping(target = "primary", source = "primaryImage")
    ProductDtos.ProductImageResponse toImageResponse(ProductImage image);

    @Mapping(target = "name", source = "product.name")
    @Mapping(target = "description", source = "product.description")
    @Mapping(target = "price", source = "product.price")
    ProductDto toDto(Product product);
}
