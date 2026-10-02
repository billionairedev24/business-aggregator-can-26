/**
 * PCI DSS SAQ A support (S-110, docs/compliance/pci): recognising cardholder data so the api can refuse it and the
 * scanners can prove the schema, seeds, contracts and logs never hold it.
 */
@NullMarked
package ca.northline.platform.pci;

import org.jspecify.annotations.NullMarked;
