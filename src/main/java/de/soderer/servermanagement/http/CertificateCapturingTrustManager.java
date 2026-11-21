package de.soderer.servermanagement.http;

import java.net.Socket;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Trust manager which remembers the certificate of the server of the first TLS handshake and leaves the trust
 * decision to the wrapped trust manager, e.g. to show the expiry date of the server certificate.
 *
 * <p>
 * The certificate is remembered before the trust check, so it is also available if the check fails (e.g. for an
 * expired certificate). Use one instance per request.
 * </p>
 */
public class CertificateCapturingTrustManager extends X509ExtendedTrustManager {
	/** Trust manager making the trust decision */
	private final X509TrustManager delegate;

	/** Certificate of the server (first certificate of the chain of the first handshake), null if none was seen yet */
	private volatile X509Certificate serverCertificate = null;

	/**
	 * Creates a capturing trust manager.
	 *
	 * @param delegate trust manager making the trust decision
	 */
	public CertificateCapturingTrustManager(final X509TrustManager delegate) {
		this.delegate = delegate;
	}

	/**
	 * Returns the certificate of the server.
	 *
	 * @return certificate of the server, null if no TLS handshake took place (e.g. plain http or no connection)
	 */
	public X509Certificate getServerCertificate() {
		return serverCertificate;
	}

	/**
	 * Remembers the server certificate of the first handshake. A redirect to another host must not replace the
	 * certificate of the checked URL.
	 */
	private void capture(final X509Certificate[] chain) {
		if (serverCertificate == null && chain != null && chain.length > 0) {
			serverCertificate = chain[0];
		}
	}

	@Override
	public void checkServerTrusted(final X509Certificate[] chain, final String authType) throws CertificateException {
		capture(chain);
		delegate.checkServerTrusted(chain, authType);
	}

	@Override
	public void checkServerTrusted(final X509Certificate[] chain, final String authType, final Socket socket) throws CertificateException {
		capture(chain);
		if (delegate instanceof final X509ExtendedTrustManager extendedDelegate) {
			// Keeps the endpoint identification of the extended trust manager
			extendedDelegate.checkServerTrusted(chain, authType, socket);
		} else {
			delegate.checkServerTrusted(chain, authType);
		}
	}

	@Override
	public void checkServerTrusted(final X509Certificate[] chain, final String authType, final SSLEngine engine) throws CertificateException {
		capture(chain);
		if (delegate instanceof final X509ExtendedTrustManager extendedDelegate) {
			// Keeps the endpoint identification of the extended trust manager
			extendedDelegate.checkServerTrusted(chain, authType, engine);
		} else {
			delegate.checkServerTrusted(chain, authType);
		}
	}

	@Override
	public void checkClientTrusted(final X509Certificate[] chain, final String authType) throws CertificateException {
		delegate.checkClientTrusted(chain, authType);
	}

	@Override
	public void checkClientTrusted(final X509Certificate[] chain, final String authType, final Socket socket) throws CertificateException {
		if (delegate instanceof final X509ExtendedTrustManager extendedDelegate) {
			extendedDelegate.checkClientTrusted(chain, authType, socket);
		} else {
			delegate.checkClientTrusted(chain, authType);
		}
	}

	@Override
	public void checkClientTrusted(final X509Certificate[] chain, final String authType, final SSLEngine engine) throws CertificateException {
		if (delegate instanceof final X509ExtendedTrustManager extendedDelegate) {
			extendedDelegate.checkClientTrusted(chain, authType, engine);
		} else {
			delegate.checkClientTrusted(chain, authType);
		}
	}

	@Override
	public X509Certificate[] getAcceptedIssuers() {
		return delegate.getAcceptedIssuers();
	}
}
