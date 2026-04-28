package com.kaknnea.pos.mapper;

import com.kaknnea.pos.domain.Category;
import com.kaknnea.pos.dto.CategoryDtos;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-04-27T16:26:53+0700",
    comments = "version: 1.5.5.Final, compiler: Eclipse JDT (IDE) 3.46.0.v20260407-0427, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class CategoryMapperImpl implements CategoryMapper {

    @Override
    public CategoryDtos.CategoryResponse toResponse(Category category) {
        if ( category == null ) {
            return null;
        }

        CategoryDtos.CategoryResponse categoryResponse = new CategoryDtos.CategoryResponse();

        categoryResponse.setParentId( categoryParentId( category ) );
        categoryResponse.setActive( category.isActive() );
        categoryResponse.setId( category.getId() );
        categoryResponse.setNameEn( category.getNameEn() );
        categoryResponse.setNameKm( category.getNameKm() );

        return categoryResponse;
    }

    private Long categoryParentId(Category category) {
        if ( category == null ) {
            return null;
        }
        Category parent = category.getParent();
        if ( parent == null ) {
            return null;
        }
        Long id = parent.getId();
        if ( id == null ) {
            return null;
        }
        return id;
    }
}
