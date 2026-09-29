package ca.northline.booking;

import org.springframework.data.repository.ListCrudRepository;

interface QuoteRepository extends ListCrudRepository<Quote, String> {}
