package org.hyperic.util.security;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.apache.http.conn.ssl.AbstractVerifier;
import org.apache.http.conn.ssl.AllowAllHostnameVerifier;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.hyperic.sigar.Sigar;
import org.hyperic.sigar.SigarException;
import org.hyperic.util.exec.Execute;
import org.hyperic.util.exec.ExecuteWatchdog;
import org.hyperic.util.exec.PumpStreamHandler;
import org.springframework.stereotype.Component;

@Component
public class DefaultSSLProviderImpl implements SSLProvider {
	private SSLContext sslContext;
	private SSLSocketFactory sslSocketFactory;
	
    private final static String KEYSTORE_PASSWORD = "cl1ent!";
    private final static String KEYSTORE_PATH = "hq.truststore";

    private KeyStore getKeyStore() throws KeyStoreException, IOException {
    	FileInputStream keyStoreFileInputStream = null;

        try {
        	KeyStore keystore = KeyStore.getInstance(KeyStore.getDefaultType());
            File file = new File(KEYSTORE_PATH);
            char[] password = null;
            
            if (!file.exists()) {
            	generateInternalKeystore(file, "HQ");
            }
            
            // ...keystore exist, so init the file input stream...
            keyStoreFileInputStream = new FileInputStream(file);
            password = KEYSTORE_PASSWORD.toCharArray();
            
            keystore.load(keyStoreFileInputStream, password);

            return keystore;
        } catch (NoSuchAlgorithmException e) {
        	// can't check integrity of keystore, if this happens we're kind of screwed
        	// is there anything we can do to self heal this problem?
			e.printStackTrace();

			throw new IOException(e);
		} catch (CertificateException e) {
			// there are some corrupted certificates in the keystore, a bad thing
			// is there anything we can do to self heal this problem?
			e.printStackTrace();

			throw new IOException(e);
		} finally {
            if (keyStoreFileInputStream != null) {
            	keyStoreFileInputStream.close();
            	keyStoreFileInputStream = null;
            }
        }
    }

    private void generateInternalKeystore(File trustStoreFile, String alias) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        String javaHome = System.getProperty("java.home");
        String keytool = javaHome + File.separator + "bin" + File.separator + "keytool";
        String[] args = {
            keytool,
            "-genkey",
            "-dname",     "CN=" + getFQDN() +  " (HQ Self-Signed Cert), OU=Unknown, O=Unknown, L=Unknown, ST=Unknown, C=Unknown",
            "-alias",     alias,
            "-keystore",  trustStoreFile.getAbsolutePath(),
            "-storepass", KEYSTORE_PASSWORD,
            "-keypass",   KEYSTORE_PASSWORD,
            "-keyalg",    "RSA"
        };

        int timeout = 5 * 60 * 1000; //5min
        ExecuteWatchdog wdog = new ExecuteWatchdog(timeout);
        Execute exec = new Execute(new PumpStreamHandler(output), wdog);
        
        exec.setCommandline(args);

        int rc;
        
		try {
			rc = exec.execute();
		} catch (Exception e) {
			rc = -1;
		}
        
        if (rc != 0) {
            String msg = output.toString().trim();

            if (msg.length() == 0) {
                msg = "timeout after " + timeout + "ms";
            }
            
            throw new IllegalStateException(msg);
        }
    }

    private String getFQDN() {
        String address;
        final String loopback = "127.0.0.1";

        try {
            address = InetAddress.getLocalHost().getCanonicalHostName();
            
            if (!loopback.equals(address)) {
                return address;
            }
        } catch(UnknownHostException e) {
            //hostname not in DNS or /etc/hosts
        }

        Sigar sigar = new Sigar();
        
        try {
            address = sigar.getFQDN();
        } catch (SigarException e) {
            address = loopback;
        } finally {
            sigar.close();
        }

        return address;
    }
    
    private KeyManagerFactory getKeyManagerFactory(final KeyStore keystore) throws KeyStoreException, IOException {
    	try {
    		KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
    		
    		keyManagerFactory.init(keystore, KEYSTORE_PASSWORD.toCharArray());
    		
    		return keyManagerFactory;
		} catch (NoSuchAlgorithmException e) {
			// no support for algorithm, if this happens we're kind of screwed
        	// we're using the default so it should never happen
			e.printStackTrace();

			throw new IOException(e);
		} catch (UnrecoverableKeyException e) {
			// invalid password, should never happen
			e.printStackTrace();
			
			throw new IOException(e);
		}
    }
    
    private TrustManagerFactory getTrustManagerFactory(final KeyStore keystore) throws KeyStoreException, IOException {
    	try {
	    	TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
	    	
	    	trustManagerFactory.init(keystore);
	    	
	    	return trustManagerFactory;
    	} catch (NoSuchAlgorithmException e) {
    		// no support for algorithm, if this happens we're kind of screwed
        	// we're using the default so it should never happen
			e.printStackTrace();

			throw new IOException(e);
		}
    }
	
    public DefaultSSLProviderImpl() {
    	this(false);
    }
    
	public DefaultSSLProviderImpl(final boolean acceptUnverifiedCertificates) {
    	try {
	        final KeyStore trustStore = getKeyStore();
	        KeyManagerFactory keyManagerFactory = getKeyManagerFactory(trustStore);
	        TrustManagerFactory trustManagerFactory = getTrustManagerFactory(trustStore);
	        
	        final X509TrustManager defaultTrustManager = (X509TrustManager) trustManagerFactory.getTrustManagers()[0];
	        
	        X509TrustManager customTrustManager = new X509TrustManager() {
				public X509Certificate[] getAcceptedIssuers() {
					return defaultTrustManager.getAcceptedIssuers();
				}
				
				public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
					try {
						defaultTrustManager.checkServerTrusted(chain, authType);
					} catch(Exception e) {
						if (!acceptUnverifiedCertificates) {
							throw new CertificateException(e);
						} else {
							FileOutputStream keyStoreFileOutputStream = null;
					        int i=0;
					        
					        try {
					        	keyStoreFileOutputStream = new FileOutputStream(KEYSTORE_PATH);
					        	
						        for (X509Certificate cert : chain) {
						        	String[] cnValues = AbstractVerifier.getCNs(cert);
						        	String alias = cnValues[0];
						        	
						        	trustStore.setCertificateEntry(alias, cert);
						        }
						        
						        trustStore.store(keyStoreFileOutputStream, KEYSTORE_PASSWORD.toCharArray());
					        } catch (FileNotFoundException fnfe) {
								// Bad news here
								fnfe.printStackTrace();
							} catch (KeyStoreException ke) {
								// TODO Auto-generated catch block
								ke.printStackTrace();
							} catch (NoSuchAlgorithmException nsae) {
								// TODO Auto-generated catch block
								nsae.printStackTrace();
							} catch (IOException ioe) {
								// TODO Auto-generated catch block
								ioe.printStackTrace();
							} finally {
					        	if (keyStoreFileOutputStream != null) {
					        		try { 
					        			keyStoreFileOutputStream.close();
					        		} catch(IOException ioe) {}
					        		
					        		keyStoreFileOutputStream = null;
					        	}
					        }
						}
					}
				}
				
				public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
					defaultTrustManager.checkClientTrusted(chain, authType);
				}
			};
	        
	        sslContext = SSLContext.getInstance("TLS");
	        
	        sslContext.init(keyManagerFactory.getKeyManagers(), new TrustManager[] { customTrustManager }, new SecureRandom());
	        
	        // XXX Should we use ALLOW_ALL_HOSTNAME_VERIFIER (least restrictive) or 
	        //     BROWSER_COMPATIBLE_HOSTNAME_VERIFIER (moderate restrictive) or
	        //     STRICT_HOSTNAME_VERIFIER (most restrictive)???
	        sslSocketFactory = new SSLSocketFactory(sslContext, new X509HostnameVerifier() {
				private AllowAllHostnameVerifier internalVerifier = new AllowAllHostnameVerifier();
				
				public boolean verify(String host, SSLSession session) {
					return internalVerifier.verify(host, session);
				}
				
				public void verify(String host, String[] cns, String[] subjectAlts) throws SSLException {
					internalVerifier.verify(host, cns, subjectAlts);
				}
				
				public void verify(String host, X509Certificate cert) throws SSLException {
					internalVerifier.verify(host, cert);
				}
				
				public void verify(String host, SSLSocket ssl) throws IOException {
					try {
						internalVerifier.verify(host, ssl);
					} catch(SSLPeerUnverifiedException e) {
						throw new SSLPeerUnverifiedException("The authenticity of host '" + host + "' can't be established.");
					}
				}
			});
    	} catch(Exception e) {
    		throw new IllegalStateException(e);
    	}
    }
	
	public SSLContext getSSLContext() {
		return sslContext;
	}

	public SSLSocketFactory getSSLSocketFactory() {
		return sslSocketFactory;
	}
}