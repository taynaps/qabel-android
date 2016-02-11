package de.qabel.qabelbox.services;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.util.Log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

import java.io.FileNotFoundException;
import java.net.URI;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.qabel.core.config.Contact;
import de.qabel.core.config.Contacts;
import de.qabel.core.config.Identities;
import de.qabel.core.config.Identity;
import de.qabel.core.crypto.AbstractBinaryDropMessage;
import de.qabel.core.crypto.BinaryDropMessageV0;
import de.qabel.core.crypto.CryptoUtils;
import de.qabel.core.drop.DropMessage;
import de.qabel.core.drop.DropURL;
import de.qabel.core.exceptions.QblDropInvalidMessageSizeException;
import de.qabel.core.exceptions.QblDropPayloadSizeException;
import de.qabel.core.exceptions.QblInvalidEncryptionKeyException;
import de.qabel.core.exceptions.QblSpoofedSenderException;
import de.qabel.core.exceptions.QblVersionMismatchException;
import de.qabel.core.http.DropHTTP;
import de.qabel.core.http.HTTPResult;
import de.qabel.qabelbox.config.AndroidPersistence;
import de.qabel.qabelbox.config.QblSQLiteParams;
import de.qabel.qabelbox.providers.DocumentIdParser;
import de.qabel.qabelbox.storage.BoxUploadingFile;

public class LocalQabelService extends Service {

    private final static Logger LOGGER = LoggerFactory.getLogger(LocalQabelService.class.getName());

    private static final String TAG = "LocalQabelService";
    private static final String PREF_LAST_ACTIVE_IDENTITY = "PREF_LAST_ACTIVE_IDENTITY";
    public static final String DEFAULT_DROP_SERVER = "http://localhost";

    private static final String PREF_DEVICE_ID_CREATED = "PREF_DEVICE_ID_CREATED";
    private static final String PREF_DEVICE_ID = "PREF_DEVICE_ID";
    private static final int NUM_BYTES_DEVICE_ID = 16;

    private final IBinder mBinder = new LocalBinder();

    protected static final String DB_NAME = "qabel-service";
    protected static final int DB_VERSION = 1;
    protected AndroidPersistence persistence;
    private DropHTTP dropHTTP;
    private HashMap<String, ArrayList<BoxUploadingFile>> pendingUploads;
    private DocumentIdParser documentIdParser;

    SharedPreferences sharedPreferences;

    protected void setLastActiveIdentityID(String identityID) {
        sharedPreferences.edit()
                .putString(PREF_LAST_ACTIVE_IDENTITY, identityID)
                .apply();
    }

    protected String getLastActiveIdentityID() {
        return sharedPreferences.getString(PREF_LAST_ACTIVE_IDENTITY, "");
    }

    public void addIdentity(Identity identity) {
        persistence.updateOrPersistEntity(identity);
    }

    public Identities getIdentities() {
        List<Identity> entities = persistence.getEntities(Identity.class);
        Identities identities = new Identities();
        for (Identity i : entities) {
            identities.put(i);
        }
        return identities;
    }

    public Identity getActiveIdentity() {
        String identityID = getLastActiveIdentityID();
        return getIdentities().getByKeyIdentifier(identityID);
    }

    public void setActiveIdentity(Identity identity) {
        setLastActiveIdentityID(identity.getKeyIdentifier());
    }

    public void deleteIdentity(Identity identity) {
        persistence.removeEntity(identity.getPersistenceID(), Identity.class);
    }

    /**
     * Modify the identity in place
     *
     * @param identity known identity with modifid data
     */
    public void modifyIdentity(Identity identity) {
        persistence.updateEntity(identity);
    }

    /**
     * Create a list of all contacts that are known, regardless of the identity that owns it
     *
     * @return List of all contacts
     */
    public Contacts getContacts() {
        return getContacts(getActiveIdentity());
    }

    /**
     * Create a list of contacts for the given Identity
     *
     * @param identity selected identity
     * @return List of contacts owned by the identity
     */
    public Contacts getContacts(Identity identity) {
        List<Contacts> entities = persistence.getEntities(Contacts.class);
        for (Contacts contacts: entities) {
            if (contacts.getIdentity().equals(identity)) {
                return contacts;
            }
        }
        Contacts contacts = new Contacts(identity);
        persistence.updateOrPersistEntity(contacts);
        return contacts;
    }

    public void addContact(Contact contact) {
        persistence.updateEntity(getContacts().put(contact));
    }

    public void deleteContact(Contact contact) {
        persistence.updateEntity(getContacts().remove(contact));
    }

    public void modifyContact(Contact contact) {
        Contacts contacts = getContacts();
        contacts.remove(contact);
        contacts.put(contact);
        persistence.updateEntity(contacts);
    }

    /**
     * Create a map that maps each known identity to all of its contacts
     *
     * @return Map of each identity to its contacts
     */
    public Map<Identity, Contacts> getAllContacts() {
        Map<Identity, Contacts> contactMap = new HashMap<>();
        for (Contacts contacts: persistence.getEntities(Contacts.class)) {
            contactMap.put(contacts.getIdentity(), contacts);
        }
        return contactMap;
    }

    public interface OnSendDropMessageResult {
        void onSendDropResult(Map<DropURL, Boolean> deliveryStatus);
    }

    /**
     * Sends {@link DropMessage} to a {@link Contact} in a new thread. Returns without blocking.
     *
     * @param dropMessage        {@link DropMessage} to send.
     * @param recipient          {@link Contact} to send {@link DropMessage} to.
     * @param dropResultCallback Callback to Map<DropURL, Boolean> deliveryStatus which contains
     *                           sending status to DropURLs of the recipient. Can be null if status is irrelevant.
     * @throws QblDropPayloadSizeException
     */
    public void sendDropMessage(final DropMessage dropMessage, final Contact recipient,
                                final Identity identity,
                                @Nullable final OnSendDropMessageResult dropResultCallback)
            throws QblDropPayloadSizeException {
        new Thread(new Runnable() {
            final BinaryDropMessageV0 binaryMessage = new BinaryDropMessageV0(dropMessage);
            final byte[] messageByteArray = binaryMessage.assembleMessageFor(recipient, identity);
            HashMap<DropURL, Boolean> deliveryStatus = new HashMap<>();

            @Override
            public void run() {
                for (DropURL dropURL : recipient.getDropUrls()) {
                    HTTPResult<?> dropResult = dropHTTPsend(dropURL, messageByteArray);
                    if (dropResult.getResponseCode() == 200) {
                        deliveryStatus.put(dropURL, true);
                    } else {
                        deliveryStatus.put(dropURL, false);
                    }
                }
                if (dropResultCallback != null) {
                    dropResultCallback.onSendDropResult(deliveryStatus);
                }
            }
        }).start();
    }

    /**
     * Send DropMessages via DropHTTP. Method extracted to mock send in LocalQabelServiceTester.
     *
     * @param dropURL DropURL to send DropMessage to.
     * @param message Encrypted DropMessage
     * @return
     */
    HTTPResult<?> dropHTTPsend(DropURL dropURL, byte[] message) {
        return dropHTTP.send(dropURL.getUri(), message);
    }

    /**
     * Retrieves all DropMessages all Identities
     *
     * @return Retrieved, decrypted DropMessages.
     */
    public Collection<DropMessage> retrieveDropMessages() {
        Collection<DropMessage> allMessages = new ArrayList<>();
        for (Identity identity : getIdentities().getIdentities()) {
            for (DropURL dropUrl : identity.getDropUrls()) {
                Collection<DropMessage> results = this.retrieveDropMessages(dropUrl.getUri());
                allMessages.addAll(results);
            }
        }
        return allMessages;
    }

    /**
     * Retrieves all DropMessages from given URI
     *
     * @param uri URI where to retrieve the drop from
     * @return Retrieved, decrypted DropMessages.
     */
    public Collection<DropMessage> retrieveDropMessages(URI uri) {
        HTTPResult<Collection<byte[]>> cipherMessages = getDropMessages(uri);
        Collection<DropMessage> plainMessages = new ArrayList<>();

        List<Contact> ccc = new ArrayList<>(getContacts().getContacts());
        Collections.shuffle(ccc, new SecureRandom());

        for (byte[] cipherMessage : cipherMessages.getData()) {
            AbstractBinaryDropMessage binMessage;
            byte binaryFormatVersion = cipherMessage[0];

            switch (binaryFormatVersion) {
                case 0:
                    try {
                        binMessage = new BinaryDropMessageV0(cipherMessage);
                    } catch (QblVersionMismatchException e) {
                        LOGGER.error("Version mismatch in binary drop message", e);
                        throw new RuntimeException("Version mismatch should not happen", e);
                    } catch (QblDropInvalidMessageSizeException e) {
                        LOGGER.info("Binary drop message version 0 with unexpected size discarded.");
                        // Invalid message uploads may happen with malicious intent
                        // or by broken clients. Skip.
                        continue;
                    }
                    break;
                default:
                    LOGGER.warn("Unknown binary drop message version " + binaryFormatVersion);
                    // cannot handle this message -> skip
                    continue;
            }
            for (Identity identity : getIdentities().getIdentities()) {
                DropMessage dropMessage;
                try {
                    dropMessage = binMessage.disassembleMessage(identity);
                } catch (QblSpoofedSenderException e) {
                    //TODO: Notify the user about the spoofed message
                    break;
                }
                if (dropMessage != null) {
                    for (Contact c : ccc) {
                        if (c.getKeyIdentifier().equals(dropMessage.getSenderKeyId())) {
                            if (dropMessage.registerSender(c)) {
                                plainMessages.add(dropMessage);
                                break;
                            }
                        }
                    }
                    break;
                }
            }
        }
        return plainMessages;
    }

    /**
     * Receives DropMessages via DropHTTP. Method extracted to mock receive in LocalQabelServiceTester.
     *
     * @param uri URI to receive DropMessages from
     * @return HTTPResult with collection of encrypted DropMessages.
     */
    HTTPResult<Collection<byte[]>> getDropMessages(URI uri) {
        return dropHTTP.receiveMessages(uri);
    }

    public class LocalBinder extends Binder {
        public LocalQabelService getService() {
            // Return this instance of LocalQabelService so clients can call public methods
            return LocalQabelService.this;
        }
    }

    public byte[] getDeviceID() {
        String deviceID = sharedPreferences.getString(PREF_DEVICE_ID, "");
        if (deviceID.equals("")) {
            // Should never occur
            throw new RuntimeException("DeviceID not created!");
        }
        return Hex.decode(deviceID);
    }

	public HashMap<String, ArrayList<BoxUploadingFile>> getPendingUploads() {
		return pendingUploads;
	}

	public void addPendingUpload(String documentId) throws FileNotFoundException {
		String uploadPath = documentIdParser.getPath(documentId);
		ArrayList<BoxUploadingFile> uploadsInPath = pendingUploads.get(uploadPath);
		if (uploadsInPath == null) {
			uploadsInPath = new ArrayList<>();
		}
		uploadsInPath.add(new BoxUploadingFile(documentIdParser.getBaseName(documentId)));
		pendingUploads.put(uploadPath, uploadsInPath);
	}

	public boolean removePendingUpload(String documentId) throws FileNotFoundException {
		String uploadPath = documentIdParser.getPath(documentId);
		ArrayList<BoxUploadingFile> uploadsInPath = pendingUploads.get(uploadPath);
		if (uploadsInPath == null) {
			return false;
		}
		for (BoxUploadingFile boxUploadingFile : uploadsInPath) {
			if (boxUploadingFile.name.equals(documentIdParser.getBaseName(documentId))) {
				uploadsInPath.remove(boxUploadingFile);
				return true;
			}
		}
		return false;
	}

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "LocalQabelService created");
        dropHTTP = new DropHTTP();
        initSharedPreferences();
        initAndroidPersistence();
		pendingUploads = new HashMap<>();
		documentIdParser = new DocumentIdParser();
    }

    protected void initAndroidPersistence() {
        AndroidPersistence androidPersistence;
        QblSQLiteParams params = new QblSQLiteParams(this, DB_NAME, null, DB_VERSION);
        try {
            androidPersistence = new AndroidPersistence(params);
        } catch (QblInvalidEncryptionKeyException e) {
            Log.e(TAG, "Invalid database password!");
            return;
        }
        this.persistence = androidPersistence;
    }

    protected void initSharedPreferences() {
        sharedPreferences = getSharedPreferences(this.getClass().getCanonicalName(), MODE_PRIVATE);
        if (!sharedPreferences.getBoolean(PREF_DEVICE_ID_CREATED, false)) {

            CryptoUtils cryptoUtils = new CryptoUtils();
            byte[] deviceID = cryptoUtils.getRandomBytes(NUM_BYTES_DEVICE_ID);

            Log.d(this.getClass().getName(), "New device ID: " + Hex.toHexString(deviceID));

            sharedPreferences.edit().putString(PREF_DEVICE_ID, Hex.toHexString(deviceID))
                    .putBoolean(PREF_DEVICE_ID_CREATED, true)
                    .apply();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

}

