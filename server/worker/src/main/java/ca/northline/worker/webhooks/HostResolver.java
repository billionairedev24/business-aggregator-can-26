package ca.northline.worker.webhooks;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * Outbound port: name → addresses. The system resolver in production; tests map made-up names to chosen addresses to
 * show that the transport connects to the checked address and nothing else.
 */
@FunctionalInterface
public interface HostResolver {

    HostResolver SYSTEM = host -> List.of(InetAddress.getAllByName(host));

    List<InetAddress> resolve(String host) throws UnknownHostException;
}
