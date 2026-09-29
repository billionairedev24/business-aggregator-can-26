/**
 * Object storage shared by every module's storage port (S-10): {@link ca.northline.shared.storage.ObjectStore} (put /
 * get / metadata / exists / delete / presigned GET) with adapters for AWS S3 and any S3-compatible server (RustFS,
 * MinIO), Google Cloud Storage and Azure Blob Storage, chosen by {@code northline.storage.provider} ({@code
 * STORAGE_PROVIDER}). Modules keep their own ports ({@code DocumentStorage}, {@code AttachmentStorage}, …) and
 * implement them over {@link ca.northline.shared.storage.ObjectStore#within(String)}, which namespaces keys per module
 * ({@code <module>/<merchantId>/<ulid>.<ext>}, see {@link ca.northline.shared.storage.ObjectKeys}). Every upload passes
 * the {@link ca.northline.shared.storage.VirusScanner} hook first. {@code local} keeps the modules' disk fakes
 * ({@link ca.northline.shared.storage.UsesLocalStorage} / {@link ca.northline.shared.storage.UsesObjectStorage} pick
 * the adapters).
 */
@NamedInterface("storage")
@NullMarked
package ca.northline.shared.storage;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
