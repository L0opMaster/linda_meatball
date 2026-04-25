package com.kaknnea.pos.mapper;

import com.kaknnea.pos.domain.Product;
import com.kaknnea.pos.domain.ProductBundleComponent;
import com.kaknnea.pos.domain.ProductImage;
import com.kaknnea.pos.dto.ProductDto;
import com.kaknnea.pos.dto.ProductDtos;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-04-08T16:26:16+0700",
    comments = "version: 1.5.5.Final, compiler: Eclipse JDT (IDE) 3.45.0.v20260224-0835, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class ProductMapperImpl implements ProductMapper {

    @Override
    public ProductDtos.ProductResponse toResponse(Product product) {
        if ( product == null ) {
            return null;
        }

        ProductDtos.ProductResponse productResponse = new ProductDtos.ProductResponse();

        productResponse.setParentProductId( product.getParentProductId() );
        productResponse.setParentProductNameEn( product.getParentProductNameEn() );
        productResponse.setResolvedPrice( product.getResolvedPrice() );
        productResponse.setActive( product.isActive() );
        productResponse.setBarcode( product.getBarcode() );
        productResponse.setBundleComponents( productBundleComponentListToProductBundleComponentResponseList( product.getBundleComponents() ) );
        productResponse.setBundleMode( product.getBundleMode() );
        productResponse.setCost( product.getCost() );
        productResponse.setId( product.getId() );
        productResponse.setImageUrl( product.getImageUrl() );
        productResponse.setImages( productImageListToProductImageResponseList( product.getImages() ) );
        productResponse.setLowStockThreshold( product.getLowStockThreshold() );
        productResponse.setNameEn( product.getNameEn() );
        productResponse.setNameKm( product.getNameKm() );
        productResponse.setPrice( product.getPrice() );
        productResponse.setProductType( product.getProductType() );
        productResponse.setPurchasable( product.isPurchasable() );
        productResponse.setSellable( product.isSellable() );
        productResponse.setSku( product.getSku() );
        productResponse.setTrackInventory( product.isTrackInventory() );
        productResponse.setVariantLabel( product.getVariantLabel() );

        return productResponse;
    }

    @Override
    public ProductDtos.ProductImageResponse toImageResponse(ProductImage image) {
        if ( image == null ) {
            return null;
        }

        ProductDtos.ProductImageResponse productImageResponse = new ProductDtos.ProductImageResponse();

        productImageResponse.setPrimary( image.isPrimaryImage() );
        productImageResponse.setId( image.getId() );
        productImageResponse.setUrl( image.getUrl() );

        return productImageResponse;
    }

    @Override
    public ProductDto toDto(Product product) {
        if ( product == null ) {
            return null;
        }

        ProductDto productDto = new ProductDto();

        productDto.setName( product.getName() );
        productDto.setDescription( product.getDescription() );
        if ( product.getPrice() != null ) {
            productDto.setPrice( product.getPrice().doubleValue() );
        }
        productDto.setId( product.getId() );

        return productDto;
    }

    protected ProductDtos.ProductBundleComponentResponse productBundleComponentToProductBundleComponentResponse(ProductBundleComponent productBundleComponent) {
        if ( productBundleComponent == null ) {
            return null;
        }

        ProductDtos.ProductBundleComponentResponse productBundleComponentResponse = new ProductDtos.ProductBundleComponentResponse();

        productBundleComponentResponse.setComponentQuantity( productBundleComponent.getComponentQuantity() );
        productBundleComponentResponse.setId( productBundleComponent.getId() );

        return productBundleComponentResponse;
    }

    protected List<ProductDtos.ProductBundleComponentResponse> productBundleComponentListToProductBundleComponentResponseList(List<ProductBundleComponent> list) {
        if ( list == null ) {
            return null;
        }

        List<ProductDtos.ProductBundleComponentResponse> list1 = new ArrayList<ProductDtos.ProductBundleComponentResponse>( list.size() );
        for ( ProductBundleComponent productBundleComponent : list ) {
            list1.add( productBundleComponentToProductBundleComponentResponse( productBundleComponent ) );
        }

        return list1;
    }

    protected List<ProductDtos.ProductImageResponse> productImageListToProductImageResponseList(List<ProductImage> list) {
        if ( list == null ) {
            return null;
        }

        List<ProductDtos.ProductImageResponse> list1 = new ArrayList<ProductDtos.ProductImageResponse>( list.size() );
        for ( ProductImage productImage : list ) {
            list1.add( toImageResponse( productImage ) );
        }

        return list1;
    }
}
