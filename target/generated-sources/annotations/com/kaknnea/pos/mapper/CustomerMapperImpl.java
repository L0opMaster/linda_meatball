package com.kaknnea.pos.mapper;

import com.kaknnea.pos.domain.Customer;
import com.kaknnea.pos.dto.CustomerDtos;
import javax.annotation.processing.Generated;
import org.springframework.stereotype.Component;

@Generated(
    value = "org.mapstruct.ap.MappingProcessor",
    date = "2026-04-25T13:29:39+0700",
    comments = "version: 1.5.5.Final, compiler: Eclipse JDT (IDE) 3.46.0.v20260407-0427, environment: Java 21.0.10 (Eclipse Adoptium)"
)
@Component
public class CustomerMapperImpl implements CustomerMapper {

    @Override
    public CustomerDtos.CustomerResponse toResponse(Customer customer) {
        if ( customer == null ) {
            return null;
        }

        CustomerDtos.CustomerResponse customerResponse = new CustomerDtos.CustomerResponse();

        customerResponse.setCode( customer.getCode() );
        customerResponse.setTotalSales( customer.getTotalSales() );
        customerResponse.setAddress( customer.getAddress() );
        customerResponse.setContactPerson( customer.getContactPerson() );
        customerResponse.setCreditBalance( customer.getCreditBalance() );
        customerResponse.setCreditHold( customer.isCreditHold() );
        customerResponse.setCreditLimit( customer.getCreditLimit() );
        customerResponse.setDisplayName( customer.getDisplayName() );
        customerResponse.setEmail( customer.getEmail() );
        customerResponse.setId( customer.getId() );
        customerResponse.setNameEn( customer.getNameEn() );
        customerResponse.setNameKm( customer.getNameKm() );
        customerResponse.setNotes( customer.getNotes() );
        customerResponse.setPaymentTerms( customer.getPaymentTerms() );
        customerResponse.setPhone( customer.getPhone() );
        customerResponse.setStatus( customer.getStatus() );
        customerResponse.setTaxNumber( customer.getTaxNumber() );
        customerResponse.setType( customer.getType() );

        return customerResponse;
    }
}
