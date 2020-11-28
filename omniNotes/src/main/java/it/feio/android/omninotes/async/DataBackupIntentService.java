/*
 * Copyright (C) 2013-2020 Federico Iosue (federico@iosue.it)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package it.feio.android.omninotes.async;

import static it.feio.android.omninotes.utils.Constants.PREFS_NAME;
import static it.feio.android.omninotes.utils.ConstantsBase.ACTION_RESTART_APP;

import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;
import it.feio.android.omninotes.MainActivity;
import it.feio.android.omninotes.OmniNotes;
import it.feio.android.omninotes.R;
import it.feio.android.omninotes.db.DbHelper;
import it.feio.android.omninotes.helpers.BackupHelper;
import it.feio.android.omninotes.helpers.LogDelegate;
import it.feio.android.omninotes.helpers.SpringImportHelper;
import it.feio.android.omninotes.models.Attachment;
import it.feio.android.omninotes.models.Note;
import it.feio.android.omninotes.models.listeners.OnAttachingFileListener;
import it.feio.android.omninotes.utils.ReminderHelper;
import it.feio.android.omninotes.utils.StorageHelper;
import it.feio.android.omninotes.helpers.notifications.NotificationChannels.NotificationChannelNames;
import it.feio.android.omninotes.helpers.notifications.NotificationsHelper;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.zip.ZipOutputStream;
import org.openintents.openpgp.IOpenPgpService2;
import org.openintents.openpgp.OpenPgpError;
import org.openintents.openpgp.util.OpenPgpApi;
import org.openintents.openpgp.util.OpenPgpServiceConnection;

public class DataBackupIntentService extends IntentService implements OnAttachingFileListener {

  public static final String INTENT_BACKUP_NAME = "backup_name";
  public static final String INTENT_OPENPGP_PROVIDER = "openpgp_provider";
  public static final String INTENT_OPENPGP_KEY = "openpgp_key";
  public static final String INTENT_SIGN_ENCRYPTED_BACKUPS = "sign_encrypted_backups";
  public static final String INTENT_BACKUP_INCLUDE_SETTINGS = "backup_include_settings";
  public static final String ACTION_DATA_EXPORT = "action_data_export";
  public static final String ACTION_DATA_IMPORT = "action_data_import";
  public static final String ACTION_DATA_IMPORT_LEGACY = "action_data_import_legacy";
  public static final String ACTION_DATA_DELETE = "action_data_delete";

  private SharedPreferences prefs;
  private NotificationsHelper mNotificationsHelper;
  private OpenPgpServiceConnection mOpenPgpConnection;

//    {
//        File autoBackupDir = StorageHelper.getBackupDir(Constants.AUTO_BACKUP_DIR);
//        BackupHelper.exportNotes(autoBackupDir);
//        BackupHelper.exportAttachments(autoBackupDir);
//    }


  public DataBackupIntentService () {
    super("DataBackupIntentService");
  }

  @Override
  protected void onHandleIntent (Intent intent) {
    prefs = getSharedPreferences(PREFS_NAME, MODE_MULTI_PROCESS);

    mNotificationsHelper = new NotificationsHelper(this).start(NotificationChannelNames.BACKUPS,
        R.drawable.ic_content_save_white_24dp, getString(R.string.working));

    // If an alarm has been fired a notification must be generated
    if (ACTION_DATA_EXPORT.equals(intent.getAction())) {
      exportData(intent);
    } else if (ACTION_DATA_IMPORT.equals(intent.getAction()) || ACTION_DATA_IMPORT_LEGACY.equals(intent.getAction())) {
      importData(intent);
    } else if (SpringImportHelper.ACTION_DATA_IMPORT_SPRINGPAD.equals(intent.getAction())) {
      importDataFromSpringpad(intent, mNotificationsHelper);
    } else if (ACTION_DATA_DELETE.equals(intent.getAction())) {
      deleteData(intent);
    }
  }

  private void importDataFromSpringpad (Intent intent, NotificationsHelper mNotificationsHelper) {
    new SpringImportHelper(OmniNotes.getAppContext()).importDataFromSpringpad(intent, mNotificationsHelper);
    String title = getString(R.string.data_import_completed);
    String text = getString(R.string.click_to_refresh_application);
    createNotification(intent, this, title, text, null);
  }

  private synchronized void exportData (Intent intent) {

    boolean result = true;

    String openPgpProvider = intent.getStringExtra(INTENT_OPENPGP_PROVIDER);
    long openPgpKey = intent.getLongExtra(INTENT_OPENPGP_KEY, 0);
    boolean encrypt = (!TextUtils.isEmpty(openPgpProvider));
    boolean signEncryptedBackups = intent.getBooleanExtra(INTENT_SIGN_ENCRYPTED_BACKUPS, true);
    
    // Gets backup folder
    boolean backupSettings = intent.getBooleanExtra(INTENT_BACKUP_INCLUDE_SETTINGS, true);
    String backupName = intent.getStringExtra(INTENT_BACKUP_NAME);
    File backupDir = (!encrypt) ? StorageHelper.getBackupDir(backupName) : StorageHelper.getBackupArchive(backupName);

    // Directory clean in case of previously used backup name
    if (backupDir.exists()) {
      StorageHelper.delete(this, backupDir.getAbsolutePath());
    }
    
    if (!encrypt) {
      // Directory is re-created in case of previously used backup name (removed above)
      backupDir = StorageHelper.getBackupDir(backupName);
    }

    if (encrypt) {
      final File backupDir2 = backupDir;
      mOpenPgpConnection = new OpenPgpServiceConnection(
        OmniNotes.getAppContext(),
        openPgpProvider,
        new OpenPgpServiceConnection.OnBound() {
          private void cleanup(boolean result) {
            mOpenPgpConnection = null;
            if (mNotificationsHelper != null) {
              String notificationMessage =
                  result ? getString(R.string.data_export_completed) : getString(R.string.data_export_failed);
              mNotificationsHelper.finish(new Intent(), notificationMessage);
            }
          }
          
          @Override
          public synchronized void onBound(IOpenPgpService2 service) {
            boolean result = false;
            if (mOpenPgpConnection != null) {
              try (PipedInputStream pis = new PipedInputStream();
                   PipedOutputStream pos = new PipedOutputStream(pis);
                   ZipOutputStream zos = new ZipOutputStream(pos);
                   FileOutputStream fos = new FileOutputStream(backupDir2)) {
                
                Intent data = new Intent();
                data.setAction((signEncryptedBackups) ? OpenPgpApi.ACTION_SIGN_AND_ENCRYPT : OpenPgpApi.ACTION_ENCRYPT);
                data.putExtra(OpenPgpApi.EXTRA_KEY_IDS, new long[]{openPgpKey});
                if (signEncryptedBackups) {
                  data.putExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, openPgpKey);
                }
                data.putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, false);

                data.putExtra(OpenPgpApi.EXTRA_ORIGINAL_FILENAME, ((backupName.endsWith(".gpg")) ?
                  backupName.substring(0, backupName.length() - 4) : backupName));
                
                // we disable openpgp gzip compression, as we use zip compression (enabled by default)
                data.putExtra(OpenPgpApi.EXTRA_ENABLE_COMPRESSION, false);
                
                // consider replacing fos with a bufferedoutputstream writing to fos
                FutureTask<Boolean> task = new FutureTask<>(() -> {
                  OpenPgpApi api = new OpenPgpApi(OmniNotes.getAppContext(), mOpenPgpConnection.getService());
                  LogDelegate.w("About to call executeApi");
                  Intent ri = api.executeApi(data, pis, fos);
                  LogDelegate.w("Finished executeApi");
                  
                  switch (ri.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
                    case OpenPgpApi.RESULT_CODE_SUCCESS:
                      LogDelegate.w("RESULT_CODE_SUCCESS");
                      return true;
                    
                    // shouldn't happen; that's why we did a test sign
                    case OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED:
                      LogDelegate.e("OpenPgp backup failed (wanted user interaction in backup service) (encrypted backup)");
                      return false;
                    
                    case OpenPgpApi.RESULT_CODE_ERROR:
                    default:
                      OpenPgpError error = ri.getParcelableExtra(OpenPgpApi.RESULT_ERROR);
                      LogDelegate.e("OpenPgp error (encrypted backup): " + error.getMessage());
                      return false;
                  }
                });
                
                ExecutorService es = Executors.newSingleThreadExecutor();
                es.execute(task);
                
                try {
                  result = BackupHelper.exportNotes(zos);
                  
                  if (result) {
                    result = BackupHelper.exportAttachments(zos, mNotificationsHelper);
                  }

                  if (result && backupSettings) {
                    result = BackupHelper.exportSettings(zos);
                  }
                } catch (Exception e) {
                  LogDelegate.e("Encrypted backup failed (in export sequence try block): " + e);
                  result = false;
                }

                LogDelegate.w("export data write done");
                
                try {
                  zos.flush();
                  zos.close();
                  pos.flush();
                  pos.close();
                  
                  LogDelegate.w("about to wait on api thread");
                  
                  boolean result2 = task.get();
                  if (result) {
                    result = result2;
                  }
                  
                  es.shutdown();
                }
                catch (Exception e) {
                  LogDelegate.e("Encrypted backup failed (in openpgp wait try block): " + e);
                  result = false;
                }
              } catch (Exception e) {
                LogDelegate.e("Encrypted backup failed (in outer try block): " + e);
                result = false;
              }
            } else {
              LogDelegate.w("Encrypted backup service connection callback called but service connection is null");
            }
            cleanup(result);
            
            LogDelegate.w("Exiting OnBound");
          }

          @Override
          public synchronized void onError(Exception e) {
            LogDelegate.e("OpenPgp exception on service binding (encrypted backup): " + e);
            cleanup(false);
          }
        }
      );
      mOpenPgpConnection.bindToService();      
    } else {
      BackupHelper.exportNotes(backupDir);

      result = BackupHelper.exportAttachments(backupDir, mNotificationsHelper);

      if (backupSettings) {
        BackupHelper.exportSettings(backupDir);
      }
      
      String notificationMessage =
          result ? getString(R.string.data_export_completed) : getString(R.string.data_export_failed);
      mNotificationsHelper.finish(intent, notificationMessage);
    }
  }


  private synchronized void importData (Intent intent) {

    boolean importLegacy = ACTION_DATA_IMPORT_LEGACY.equals(intent.getAction());

    // Gets backup folder
    String backupName = intent.getStringExtra(INTENT_BACKUP_NAME);
    File backupDir = importLegacy ? new File(backupName) : StorageHelper.getBackupDir(backupName);

    BackupHelper.importSettings(backupDir);

    if (importLegacy) {
      BackupHelper.importDB(this, backupDir);
    } else {
      BackupHelper.importNotes(backupDir);
    }

    BackupHelper.importAttachments(backupDir, mNotificationsHelper);

    resetReminders();

    mNotificationsHelper.cancel();

    createNotification(intent, this, getString(R.string.data_import_completed),
        getString(R.string.click_to_refresh_application), backupDir);

    // Performs auto-backup filling after backup restore
//        if (prefs.getBoolean(Constants.PREF_ENABLE_AUTOBACKUP, false)) {
//            File autoBackupDir = StorageHelper.getBackupDir(Constants.AUTO_BACKUP_DIR);
//            BackupHelper.exportNotes(autoBackupDir);
//            BackupHelper.exportAttachments(autoBackupDir);
//        }
  }

  private synchronized void deleteData (Intent intent) {

    // Gets backup folder
    String backupName = intent.getStringExtra(INTENT_BACKUP_NAME);
    File backupDir = StorageHelper.getBackupDir(backupName);

    // Backups directory removal
    StorageHelper.delete(this, backupDir.getAbsolutePath());

    String title = getString(R.string.data_deletion_completed);
    String text = backupName + " " + getString(R.string.deleted);
    createNotification(intent, this, title, text, backupDir);
  }


  /**
   * Creation of notification on operations completed
   */
  private void createNotification (Intent intent, Context mContext, String title, String message, File backupDir) {

    // The behavior differs depending on intent action
    Intent intentLaunch;
    if (DataBackupIntentService.ACTION_DATA_IMPORT.equals(intent.getAction())
        || SpringImportHelper.ACTION_DATA_IMPORT_SPRINGPAD.equals(intent.getAction())) {
      intentLaunch = new Intent(mContext, MainActivity.class);
      intentLaunch.setAction(ACTION_RESTART_APP);
    } else {
      intentLaunch = new Intent();
    }
    // Add this bundle to the intent
    intentLaunch.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    intentLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    // Creates the PendingIntent
    PendingIntent notifyIntent = PendingIntent.getActivity(mContext, 0, intentLaunch,
        PendingIntent.FLAG_UPDATE_CURRENT);

    NotificationsHelper notificationsHelper = new NotificationsHelper(mContext);
    notificationsHelper.createStandardNotification(NotificationChannelNames.BACKUPS,
        R.drawable.ic_content_save_white_24dp, title, notifyIntent)
                        .setMessage(message).setRingtone(prefs.getString("settings_notification_ringtone", null))
                        .setLedActive();
    if (prefs.getBoolean("settings_notification_vibration", true)) {
      notificationsHelper.setVibration();
    }
    notificationsHelper.show();
  }


  /**
   * Schedules reminders
   */
  private void resetReminders () {
    LogDelegate.d("Resettings reminders");
    for (Note note : DbHelper.getInstance().getNotesWithReminderNotFired()) {
      ReminderHelper.addReminder(OmniNotes.getAppContext(), note);
    }
  }


  @Override
  public void onAttachingFileErrorOccurred (Attachment mAttachment) {
    // TODO Auto-generated method stub
  }


  @Override
  public void onAttachingFileFinished (Attachment mAttachment) {
    // TODO Auto-generated method stub
  }

}
