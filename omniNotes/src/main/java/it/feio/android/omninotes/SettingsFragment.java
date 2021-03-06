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
package it.feio.android.omninotes;

import static android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI;
import static android.provider.Settings.System.DEFAULT_NOTIFICATION_URI;
import static it.feio.android.omninotes.utils.Constants.PREFS_NAME;
import static it.feio.android.omninotes.utils.ConstantsBase.DATABASE_NAME;
import static it.feio.android.omninotes.utils.ConstantsBase.DATE_FORMAT_EXPORT;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_SWIPE_TO_TRASH;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_AUTO_LOCATION;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_COLORS_APP_DEFAULT;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_ENABLE_FILE_LOGGING;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_ENCRYPT_BACKUPS;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_SIGN_ENCRYPTED_BACKUPS;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_MAX_VIDEO_SIZE;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_OPENPGP_PROVIDER;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_OPENPGP_KEY;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_PASSWORD;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_SHOW_UNCATEGORIZED;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_SNOOZE_DEFAULT;
import static it.feio.android.omninotes.utils.ConstantsBase.PREF_TOUR_COMPLETE;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.reverse;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreference;
import com.afollestad.materialdialogs.MaterialDialog;
import com.afollestad.materialdialogs.folderselector.FolderChooserDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import it.feio.android.analitica.AnalyticsHelper;
import it.feio.android.omninotes.async.DataBackupIntentService;
import it.feio.android.omninotes.helpers.AppVersionHelper;
import it.feio.android.omninotes.helpers.BackupHelper;
import it.feio.android.omninotes.helpers.LanguageHelper;
import it.feio.android.omninotes.helpers.LogDelegate;
import it.feio.android.omninotes.helpers.PermissionsHelper;
import it.feio.android.omninotes.helpers.SpringImportHelper;
import it.feio.android.omninotes.helpers.notifications.NotificationsHelper;
import it.feio.android.omninotes.models.ONStyle;
import it.feio.android.omninotes.models.PasswordValidator;
import it.feio.android.omninotes.utils.FileHelper;
import it.feio.android.omninotes.utils.IntentChecker;
import it.feio.android.omninotes.utils.PasswordHelper;
import it.feio.android.omninotes.utils.ResourcesUtils;
import it.feio.android.omninotes.utils.StorageHelper;
import it.feio.android.omninotes.utils.SystemHelper;
import it.feio.android.omninotes.widget.LongClickableSwitchPreference;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import org.openintents.openpgp.IOpenPgpService2;
import org.openintents.openpgp.OpenPgpError;
import org.openintents.openpgp.util.OpenPgpApi;
import org.openintents.openpgp.util.OpenPgpAppPreference;
import org.openintents.openpgp.util.OpenPgpKeyPreference;
import org.openintents.openpgp.util.OpenPgpServiceConnection;
import org.apache.commons.lang3.ArrayUtils;

import java.util.ArrayList;
import it.feio.android.omninotes.models.Attachment;
import it.feio.android.omninotes.db.DbHelper;
import java.io.InputStream;
import java.nio.file.Files;
import it.feio.android.omninotes.utils.StorageHelper;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.util.ArraySet;

public class SettingsFragment extends PreferenceFragmentCompat {

  private class MyOpenPgpCallback implements OpenPgpApi.IOpenPgpCallback {

    @Override
    public void onReturn(Intent result) {
      switch (result.getIntExtra(OpenPgpApi.RESULT_CODE, OpenPgpApi.RESULT_CODE_ERROR)) {
        case OpenPgpApi.RESULT_CODE_SUCCESS:
          if (!TextUtils.isEmpty(cachedBackupName) && cachedServiceConnection != null) {
            BackupHelper.startBackupService(cachedBackupName, cachedOpenPgpProvider, cachedOpenPgpKey, cachedSignEncryptedBackups);
          }
          finishOpenPgpTestSign(false);
          break;

        case OpenPgpApi.RESULT_CODE_USER_INTERACTION_REQUIRED:
          PendingIntent pi = result.getParcelableExtra(OpenPgpApi.RESULT_INTENT);
          try {
            Activity act = (Activity) getContext();
            act.startIntentSenderFromChild(
                act, pi.getIntentSender(),
                OPENPGP_TEST_SIGN_REQUEST_CODE, null, 0, 0, 0);
          } catch (IntentSender.SendIntentException e) {
            LogDelegate.e("OpenPgp failed to send user interaction intent (test sign): " + e);
          }
          break;

        case OpenPgpApi.RESULT_CODE_ERROR:
          OpenPgpError error = result.getParcelableExtra(OpenPgpApi.RESULT_ERROR);
          LogDelegate.e("OpenPgp error (test sign): " + error.getMessage());
          finishOpenPgpTestSign(true);
          break;
      }
    }
  }


  private SharedPreferences prefs;
  
  private String cachedBackupName;
  private String cachedOpenPgpProvider;
  private long cachedOpenPgpKey;
  private boolean cachedSignEncryptedBackups;
  private OpenPgpServiceConnection cachedServiceConnection;
  private ByteArrayInputStream cachedInputStream;
  
  private static final int SPRINGPAD_IMPORT = 0;
  private static final int RINGTONE_REQUEST_CODE = 100;
  public static final int OPENPGP_KEY_REQUEST_CODE = 200;
  public static final int OPENPGP_TEST_SIGN_REQUEST_CODE = 300;
  public static final String XML_NAME = "xmlName";


  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    int xmlId = R.xml.settings;
    if (getArguments() != null && getArguments().containsKey(XML_NAME)) {
      xmlId = ResourcesUtils
          .getXmlId(OmniNotes.getAppContext(), ResourcesUtils.ResourceIdentifiers.XML, String
              .valueOf(getArguments().get(XML_NAME)));
    }
    addPreferencesFromResource(xmlId);
  }

  @Override
  public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
    prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_MULTI_PROCESS);
    setTitle();
  }

  private void setTitle() {
    String title = getString(R.string.settings);
    if (getArguments() != null && getArguments().containsKey(XML_NAME)) {
      String xmlName = getArguments().getString(XML_NAME);
      if (!TextUtils.isEmpty(xmlName)) {
        int stringResourceId = getActivity().getResources()
            .getIdentifier(xmlName.replace("settings_",
                "settings_screen_"), "string", getActivity().getPackageName());
        title = stringResourceId != 0 ? getString(stringResourceId) : title;
      }
    }
    Toolbar toolbar = getActivity().findViewById(R.id.toolbar);
    if (toolbar != null) {
      toolbar.setTitle(title);
    }
  }


  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    if (item.getItemId() == android.R.id.home) {
      getActivity().onBackPressed();
    } else {
      LogDelegate.e("Wrong element choosen: " + item.getItemId());
    }
    return super.onOptionsItemSelected(item);
  }

  @Override
  public void onResume() {
    super.onResume();

    // Export notes
    Preference export = findPreference("settings_export_data");
    if (export != null) {
      export.setOnPreferenceClickListener(arg0 -> {

        boolean encrypt = prefs.getBoolean(PREF_ENCRYPT_BACKUPS, false);
        String openPgpProvider = (encrypt) ? prefs.getString(PREF_OPENPGP_PROVIDER, null) : null;
        long openPgpKey = (encrypt) ? prefs.getLong(PREF_OPENPGP_KEY, 0) : 0;
        boolean signEncryptedBackups = (encrypt) ? prefs.getBoolean(PREF_SIGN_ENCRYPTED_BACKUPS, true) : false;
        if (encrypt && (TextUtils.isEmpty(openPgpProvider) || openPgpKey == 0)) {
          new MaterialDialog.Builder(getContext())
              .content(R.string.openpgp_not_configured)
              .positiveText(R.string.ok)
              .build().show();
          return false;
        }
        
        // Inflate layout
        LayoutInflater inflater = getActivity().getLayoutInflater();
        View v = inflater.inflate(R.layout.dialog_backup_layout, null);

        // Finds actually saved backups names
        PermissionsHelper
            .requestPermission(getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE, R
                    .string.permission_external_storage,
                getActivity().findViewById(R.id.crouton_handle), () -> export
                    (v, openPgpProvider, openPgpKey, signEncryptedBackups));

        return false;
      });
    }

    // Import notes
    Preference importData = findPreference("settings_import_data");
    if (importData != null) {
      importData.setOnPreferenceClickListener(arg0 -> {
        PermissionsHelper
            .requestPermission(getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE, R
                    .string.permission_external_storage,
                getActivity().findViewById(R.id.crouton_handle), this::importNotes);
        return false;
      });
    }

    // Import legacy notes
    Preference importLegacyData = findPreference("settings_import_data_legacy");
    if (importLegacyData != null) {
      importLegacyData.setOnPreferenceClickListener(arg0 -> {

        // Finds actually saved backups names
        PermissionsHelper
            .requestPermission(getActivity(), Manifest.permission.READ_EXTERNAL_STORAGE, R
                    .string.permission_external_storage,
                getActivity().findViewById(R.id.crouton_handle), () -> new
                    FolderChooserDialog.Builder(getActivity())
                    .chooseButton(R.string.md_choose_label)
                    .show(getActivity()));
        return false;
      });
    }

//		// Autobackup feature integrity check
//		Preference backupIntegrityCheck = findPreference("settings_backup_integrity_check");
//		if (backupIntegrityCheck != null) {
//			backupIntegrityCheck.setOnPreferenceClickListener(arg0 -> {
//				List<LinkedList<DiffMatchPatch.Diff>> errors = BackupHelper.integrityCheck(StorageHelper
//						.getBackupDir(ConstantsBase.AUTO_BACKUP_DIR));
//				if (errors.isEmpty()) {
//					new MaterialDialog.Builder(activity)
//							.content("Everything is ok")
//							.positiveText(R.string.ok)
//							.build().show();
//				} else {
//					DiffMatchPatch diffMatchPatch = new DiffMatchPatch();
//					String content = Observable.from(errors).map(diffs -> diffMatchPatch.diffPrettyHtml(diffs) +
//							"<br/>").toList().toBlocking().first().toString();
//					View v = getActivity().getLayoutInflater().inflate(R.layout.webview, null);
//					((WebView) v.findViewById(R.ID.webview)).loadData(content, "text/html", null);
//					new MaterialDialog.Builder(activity)
//							.customView(v, true)
//							.positiveText(R.string.ok)
//							.negativeText("Copy to clipboard")
//							.onNegative((dialog, which) -> {
//								SystemHelper.copyToClipboard(activity, content);
//								Toast.makeText(activity, "Copied to clipboard", Toast.LENGTH_SHORT).show();
//							})
//							.build().show();
//				}
//				return false;
//			});
//		}
//
//		// Autobackup
//		final SwitchPreference enableAutobackup = (SwitchPreference) findPreference("settings_enable_autobackup");
//		if (enableAutobackup != null) {
//			enableAutobackup.setOnPreferenceChangeListener((preference, newValue) -> {
//				if ((Boolean) newValue) {
//					new MaterialDialog.Builder(activity)
//							.content(R.string.settings_enable_automatic_backup_dialog)
//							.positiveText(R.string.confirm)
//							.negativeText(R.string.cancel)
//							.onPositive((dialog, which) -> {
//								PermissionsHelper.requestPermission(getActivity(), Manifest.permission
//										.WRITE_EXTERNAL_STORAGE, R
//										.string.permission_external_storage, activity.findViewById(R.ID
//										.crouton_handle), () -> {
//									BackupHelper.startBackupService(AUTO_BACKUP_DIR);
//									enableAutobackup.setChecked(true);
//								});
//							})
//							.build().show();
//				} else {
//					enableAutobackup.setChecked(false);
//				}
//				return false;
//			});
//		}

    Preference importFromSpringpad = findPreference("settings_import_from_springpad");
    if (importFromSpringpad != null) {
      importFromSpringpad.setOnPreferenceClickListener(arg0 -> {
        Intent intent;
        intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        if (!IntentChecker.isAvailable(getActivity(), intent, null)) {
          Toast.makeText(getActivity(), R.string.feature_not_available_on_this_device,
              Toast.LENGTH_SHORT).show();
          return false;
        }
        startActivityForResult(intent, SPRINGPAD_IMPORT);
        return false;
      });
    }

//		Preference syncWithDrive = findPreference("settings_backup_drive");
//		importFromSpringpad.setOnPreferenceClickListener(new OnPreferenceClickListener() {
//			@Override
//			public boolean onPreferenceClick(Preference arg0) {
//				Intent intent;
//				intent = new Intent(Intent.ACTION_GET_CONTENT);
//				intent.addCategory(Intent.CATEGORY_OPENABLE);
//				intent.setType("application/zip");
//				if (!IntentChecker.isAvailable(getActivity(), intent, null)) {
//					Crouton.makeText(getActivity(), R.string.feature_not_available_on_this_device,
// ONStyle.ALERT).show();
//					return false;
//				}
//				startActivityForResult(intent, SPRINGPAD_IMPORT);
//				return false;
//			}
//		});

    final LongClickableSwitchPreference encryptBackups = findPreference(PREF_ENCRYPT_BACKUPS);
    if (encryptBackups != null) {
      encryptBackups.setOnPreferenceLongClickListener(v -> {
        new MaterialDialog.Builder(getContext())
            .content(R.string.settings_encrypt_backups_summary_2)
            .positiveText(R.string.ok)
            .build().show();
        return false;
      });
    }
    
    final OpenPgpAppPreference openPgpProvider = findPreference(PREF_OPENPGP_PROVIDER);
    final OpenPgpKeyPreference openPgpKey = findPreference(PREF_OPENPGP_KEY);
    if (openPgpKey != null) {
      openPgpKey.setIntentRequestCode(OPENPGP_KEY_REQUEST_CODE);
      if (openPgpProvider != null) {
        openPgpKey.setOpenPgpProvider(openPgpProvider.getValue());
        openPgpProvider.setOnPreferenceChangeListener((preference, newValue) -> {
          openPgpKey.setOpenPgpProvider((String) newValue);
          return true;
        });
      }
    }

    // Swiping action
    final SwitchPreference swipeToTrash = findPreference(PREF_SWIPE_TO_TRASH);
    if (swipeToTrash != null) {
      if (prefs.getBoolean("settings_swipe_to_trash", false)) {
        swipeToTrash.setChecked(true);
        swipeToTrash
            .setSummary(getResources().getString(R.string.settings_swipe_to_trash_summary_2));
      } else {
        swipeToTrash.setChecked(false);
        swipeToTrash
            .setSummary(getResources().getString(R.string.settings_swipe_to_trash_summary_1));
      }
      swipeToTrash.setOnPreferenceChangeListener((preference, newValue) -> {
        if ((Boolean) newValue) {
          swipeToTrash
              .setSummary(getResources().getString(R.string.settings_swipe_to_trash_summary_2));
        } else {
          swipeToTrash
              .setSummary(getResources().getString(R.string.settings_swipe_to_trash_summary_1));
        }
        return true;
      });
    }

    // Show uncategorized notes in menu
    final SwitchPreference showUncategorized = findPreference(PREF_SHOW_UNCATEGORIZED);
    if (showUncategorized != null) {
      showUncategorized.setOnPreferenceChangeListener((preference, newValue) -> true);
    }

    // Show Automatically adds location to new notes
    final SwitchPreference autoLocation = findPreference(PREF_AUTO_LOCATION);
    if (autoLocation != null) {
      autoLocation.setOnPreferenceChangeListener((preference, newValue) -> true);
    }

    // Maximum video attachment size
    final EditTextPreference maxVideoSize = findPreference(PREF_MAX_VIDEO_SIZE);
    if (maxVideoSize != null) {
      maxVideoSize.setSummary(getString(R.string.settings_max_video_size_summary) + ": "
          + prefs.getString(PREF_MAX_VIDEO_SIZE, getString(R.string.not_set)));
      maxVideoSize.setOnPreferenceChangeListener((preference, newValue) -> {
        maxVideoSize
            .setSummary(getString(R.string.settings_max_video_size_summary) + ": " + newValue);
        prefs.edit().putString(PREF_MAX_VIDEO_SIZE, newValue.toString()).apply();
        return false;
      });
    }

    // Set notes' protection password
    Preference password = findPreference("settings_password");
    if (password != null) {
      password.setOnPreferenceClickListener(preference -> {
        Intent passwordIntent = new Intent(getActivity(), PasswordActivity.class);
        startActivity(passwordIntent);
        return false;
      });
    }

    // Use password to grant application access
    final SwitchPreference passwordAccess = findPreference("settings_password_access");
    if (passwordAccess != null) {
      if (prefs.getString(PREF_PASSWORD, null) == null) {
        passwordAccess.setEnabled(false);
        passwordAccess.setChecked(false);
      } else {
        passwordAccess.setEnabled(true);
      }
      passwordAccess.setOnPreferenceChangeListener((preference, newValue) -> {
        PasswordHelper.requestPassword(getActivity(), passwordConfirmed -> {
          if (passwordConfirmed.equals(PasswordValidator.Result.SUCCEED)) {
            passwordAccess.setChecked((Boolean) newValue);
          }
        });
        return true;
      });
    }

    // Languages
    ListPreference lang = findPreference("settings_language");
    if (lang != null) {
      String languageName = getResources().getConfiguration().locale.getDisplayName();
      lang.setSummary(
          languageName.substring(0, 1).toUpperCase(getResources().getConfiguration().locale)
              + languageName.substring(1));
      lang.setOnPreferenceChangeListener((preference, value) -> {
        LanguageHelper.updateLanguage(getActivity(), value.toString());
        SystemHelper.restartApp(getActivity().getApplicationContext(), MainActivity.class);
        return false;
      });
    }

    // Application's colors
    final ListPreference colorsApp = findPreference("settings_colors_app");
    if (colorsApp != null) {
      int colorsAppIndex = colorsApp.findIndexOfValue(prefs.getString("settings_colors_app",
          PREF_COLORS_APP_DEFAULT));
      String colorsAppString = getResources().getStringArray(R.array.colors_app)[colorsAppIndex];
      colorsApp.setSummary(colorsAppString);
      colorsApp.setOnPreferenceChangeListener((preference, newValue) -> {
        int colorsAppIndex1 = colorsApp.findIndexOfValue(newValue.toString());
        String colorsAppString1 = getResources()
            .getStringArray(R.array.colors_app)[colorsAppIndex1];
        colorsApp.setSummary(colorsAppString1);
        prefs.edit().putString("settings_colors_app", newValue.toString()).apply();
        colorsApp.setValueIndex(colorsAppIndex1);
        return false;
      });
    }

    // Checklists
    final ListPreference checklist = findPreference("settings_checked_items_behavior");
    if (checklist != null) {
      int checklistIndex = checklist
          .findIndexOfValue(prefs.getString("settings_checked_items_behavior", "0"));
      String checklistString = getResources()
          .getStringArray(R.array.checked_items_behavior)[checklistIndex];
      checklist.setSummary(checklistString);
      checklist.setOnPreferenceChangeListener((preference, newValue) -> {
        int checklistIndex1 = checklist.findIndexOfValue(newValue.toString());
        String checklistString1 = getResources().getStringArray(R.array.checked_items_behavior)
            [checklistIndex1];
        checklist.setSummary(checklistString1);
        prefs.edit().putString("settings_checked_items_behavior", newValue.toString()).apply();
        checklist.setValueIndex(checklistIndex1);
        return false;
      });
    }

    // Widget's colors
    final ListPreference colorsWidget = findPreference("settings_colors_widget");
    if (colorsWidget != null) {
      int colorsWidgetIndex = colorsWidget
          .findIndexOfValue(prefs.getString("settings_colors_widget",
              PREF_COLORS_APP_DEFAULT));
      String colorsWidgetString = getResources()
          .getStringArray(R.array.colors_widget)[colorsWidgetIndex];
      colorsWidget.setSummary(colorsWidgetString);
      colorsWidget.setOnPreferenceChangeListener((preference, newValue) -> {
        int colorsWidgetIndex1 = colorsWidget.findIndexOfValue(newValue.toString());
        String colorsWidgetString1 = getResources()
            .getStringArray(R.array.colors_widget)[colorsWidgetIndex1];
        colorsWidget.setSummary(colorsWidgetString1);
        prefs.edit().putString("settings_colors_widget", newValue.toString()).apply();
        colorsWidget.setValueIndex(colorsWidgetIndex1);
        return false;
      });
    }

    // Ringtone selection
    final Preference ringtone = findPreference("settings_notification_ringtone");
    if (ringtone != null) {
      ringtone.setOnPreferenceClickListener(arg0 -> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          new NotificationsHelper(getContext()).updateNotificationChannelsSound();
        } else {
          Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
          intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION);
          intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
          intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true);
          intent.putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, DEFAULT_NOTIFICATION_URI);

          String existingValue = prefs.getString("settings_notification_ringtone", null);
          if (existingValue != null) {
            if (existingValue.length() == 0) {
              // Select "Silent"
              intent.putExtra(EXTRA_RINGTONE_EXISTING_URI, (Uri) null);
            } else {
              intent.putExtra(EXTRA_RINGTONE_EXISTING_URI, Uri.parse(existingValue));
            }
          } else {
            // No ringtone has been selected, set to the default
            intent.putExtra(EXTRA_RINGTONE_EXISTING_URI, DEFAULT_NOTIFICATION_URI);
          }

          startActivityForResult(intent, RINGTONE_REQUEST_CODE);
        }

        return false;
      });
    }

    // Notification snooze delay
    final EditTextPreference snoozeDelay = findPreference("settings_notification_snooze_delay");
    if (snoozeDelay != null) {
      String snooze = prefs.getString("settings_notification_snooze_delay", PREF_SNOOZE_DEFAULT);
      snooze = TextUtils.isEmpty(snooze) ? PREF_SNOOZE_DEFAULT : snooze;
      snoozeDelay.setSummary(snooze + " " + getString(R.string.minutes));
      snoozeDelay.setOnPreferenceChangeListener((preference, newValue) -> {
        String snoozeUpdated = TextUtils.isEmpty(String.valueOf(newValue)) ? PREF_SNOOZE_DEFAULT
            : String.valueOf(newValue);
        snoozeDelay.setSummary(snoozeUpdated + " " + getString(R.string.minutes));
        prefs.edit().putString("settings_notification_snooze_delay", snoozeUpdated).apply();
        return false;
      });
    }

    // NotificationServiceListener shortcut
    final Preference norificationServiceListenerPreference = findPreference(
        "settings_notification_service_listener");
    if (norificationServiceListenerPreference != null) {
      getPreferenceScreen().removePreference(norificationServiceListenerPreference);
    }

    // Changelog
    Preference changelog = findPreference("settings_changelog");
    if (changelog != null) {
      changelog.setOnPreferenceClickListener(arg0 -> {

        ((OmniNotes) getActivity().getApplication()).getAnalyticsHelper()
            .trackEvent(AnalyticsHelper.CATEGORIES.SETTING,
                "settings_changelog");

        new MaterialDialog.Builder(getContext())
            .customView(R.layout.activity_changelog, false)
            .positiveText(R.string.ok)
            .build().show();
        return false;
      });
      try {
        changelog.setSummary(AppVersionHelper.getCurrentAppVersionName(getActivity()));
      } catch (NameNotFoundException e) {
        LogDelegate.e("Error retrieving version", e);
      }
    }

    // Settings reset
    Preference resetData = findPreference("reset_all_data");
    if (resetData != null) {
      resetData.setOnPreferenceClickListener(arg0 -> {

        new MaterialDialog.Builder(getContext())
            .content(R.string.reset_all_data_confirmation)
            .positiveText(R.string.confirm)
            .onPositive((dialog, which) -> {
              prefs.edit().clear().apply();
              File db = getActivity().getDatabasePath(DATABASE_NAME);
              StorageHelper.delete(getActivity(), db.getAbsolutePath());
              File attachmentsDir = StorageHelper.getAttachmentDir();
              StorageHelper.delete(getActivity(), attachmentsDir.getAbsolutePath());
              File cacheDir = StorageHelper.getCacheDir(getActivity());
              StorageHelper.delete(getActivity(), cacheDir.getAbsolutePath());
              prefs.edit().clear().apply();
              SystemHelper.restartApp(getActivity().getApplicationContext(), MainActivity.class);
            }).build().show();

        return false;
      });
    }

    // Delete Unused Attachments
    Preference deleteUnusedAttachments = findPreference("delete_unused_attachment_files");
    if (deleteUnusedAttachments != null) {
      deleteUnusedAttachments.setOnPreferenceClickListener(arg0 -> {
      
        ArrayList<Attachment> list = DbHelper.getInstance().getAllAttachments();
        //StringBuilder sb = new StringBuilder();
        File ad = StorageHelper.getAttachmentDir();
        
        ArraySet<File> files = new ArraySet<File>();
        ArraySet<File> afiles = new ArraySet<File>();

        try {
          Files.walk(ad.toPath()).forEach(path -> {
            File f = path.toFile();
            if (f.isFile()) {
              files.add(f);
            }
          });
        } catch (Exception e) {
        
        }
        
        for (Attachment a : list) {
          boolean exists = false;
          long len = 0;
          try (InputStream is = OmniNotes.getAppContext().getContentResolver().openInputStream(a.getUri())) {
            exists = true;
            len = is.available();
          } catch (Exception e) {
          
          }
          if (len < a.getLength()) {
            len = a.getLength();
          }
          if (len < a.getSize()) {
            len = a.getSize();
          }
          
          File f = new File(StorageHelper.getAttachmentDir(), a.getUriPath().substring(a.getUriPath().lastIndexOf("/") + 1)); //getFileName(a.getUri());
          if (!f.exists()) {
            exists = false;
          }
          files.remove(f);
          afiles.add(f);
          
          //sb.append("Attachment id " + a.getId() + ", uri " + a.getUriPath() + ", fn " + f + ", name " + a.getName() + ", length " + len + ", mime type " + a.getMime_type() + ", exists " + f.exists() + "\n");
        }
        
        long dellen = 0;
        for (File f : files) {
          try {
            long len = f.length();
            dellen += len;
          } catch (Exception e) {
          
          }
        }

        /*if (!files.isEmpty()) {
          sb.append("\nStragglers\n");
          for (File f : files) {
            sb.append(f + "\n");
          }
        }
        
        if (!afiles.isEmpty()) {
          sb.append("\nWill Keep\n");
          for (File f : afiles) {
            sb.append(f + "\n");
          }
        }*/
        
        if (files.isEmpty()) {
          new MaterialDialog.Builder(getContext())
              .content("No unused attachment files (out of " + afiles.size() + " total files) were found")
              .positiveText("OK")
              .build().show();
        } else {
          String s;
          if (dellen >= 1024L*1024*1024*1024) {
            s = dellen/(1024L*1024*1024*1024) + " TiB";
          } else if (dellen >= 1024L*1024*1024) {
            s = dellen/(1024L*1024*1024) + " GiB";
          } else if (dellen >= 1024L*1024) {
            s = dellen/(1024L*1024) + " MiB";
          } else if (dellen >= 1024L) {
            s = dellen/1024L + " KiB";
          } else {
            s = dellen + " bytes";
          }
          new MaterialDialog.Builder(getContext())
              .content(files.size() + " unused attachment files (out of " + (files.size() + afiles.size()) + " total files) can be deleted, saving approximately " + s + "). Would you like to delete these files?")
              .positiveText("Yes")
              .negativeText("No")
              .onPositive((dialog, which) -> {
                int successful = 0;
                for(File f : files) {
                  try {
                    if (f.delete()) {
                      successful += 1;
                    }
                  } catch (Exception e) {
                
                  }
                }
                new MaterialDialog.Builder(getContext())
                    .content((successful == files.size()) ? "All unused attachment files were successfully deleted" : (successful + " of " + files.size() + " unused attachment files were successfully deleted"))
                    .positiveText("OK")
                    .build().show();
              })
              .build().show();
        }
        return false;
      });
    }
    
    // Logs on files activation
    final SwitchPreference enableFileLogging = findPreference(PREF_ENABLE_FILE_LOGGING);
    if (enableFileLogging != null) {
      enableFileLogging.setOnPreferenceChangeListener((preference, newValue) -> {
        if ((Boolean) newValue) {
          PermissionsHelper
              .requestPermission(getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE, R
                      .string.permission_external_storage,
                  getActivity().findViewById(R.id.crouton_handle),
                  () -> enableFileLogging.setChecked(true));
        } else {
          enableFileLogging.setChecked(false);
        }
        return false;
      });
    }

    // Instructions
    Preference instructions = findPreference("settings_tour_show_again");
    if (instructions != null) {
      instructions.setOnPreferenceClickListener(arg0 -> {
        new MaterialDialog.Builder(getActivity())
            .content(getString(R.string.settings_tour_show_again_summary) + "?")
            .positiveText(R.string.confirm)
            .onPositive((dialog, which) -> {
              ((OmniNotes) getActivity().getApplication()).getAnalyticsHelper().trackEvent(
                  AnalyticsHelper.CATEGORIES.SETTING, "settings_tour_show_again");
              prefs.edit().putBoolean(PREF_TOUR_COMPLETE, false).apply();
              SystemHelper.restartApp(getActivity().getApplicationContext(), MainActivity.class);
            }).build().show();
        return false;
      });
    }
  }
  
  // https://stackoverflow.com/questions/5568874/how-to-extract-the-file-name-from-uri-returned-from-intent-action-get-content
  /*
  private static String getFileName(Uri uri) {
    String result = null;
    if (uri.getScheme().equals("content")) {
      Cursor cursor = OmniNotes.getAppContext().getContentResolver().query(uri, null, null, null, null);
      try {
        if (cursor != null && cursor.moveToFirst()) {
          result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
        }
      } finally {
        cursor.close();
      }
    }
    if (result == null) {
      result = uri.getPath();
      int cut = result.lastIndexOf('/');
      if (cut != -1) {
        result = result.substring(cut + 1);
      }
    }
    return result;
  }
  */


  private void importNotes() {
    String[] backupsArray = StorageHelper.getOrCreateExternalStoragePublicDir().list();

    if (ArrayUtils.isEmpty(backupsArray)) {
      ((SettingsActivity) getActivity()).showMessage(R.string.no_backups_available, ONStyle.WARN);
    } else {
      final List<String> backups = asList(backupsArray);
      reverse(backups);

      MaterialAlertDialogBuilder importDialog = new MaterialAlertDialogBuilder(getActivity())
          .setTitle(R.string.settings_import)
          .setSingleChoiceItems(backupsArray, -1, (dialog, position) -> {
          })
          .setPositiveButton(R.string.data_import_message, (dialog, which) -> {
            int position = ((AlertDialog) dialog).getListView().getCheckedItemPosition();
            File backupDir = StorageHelper.getOrCreateBackupDir(backups.get(position));
            long size = StorageHelper.getSize(backupDir) / 1024;
            String sizeString = size > 1024 ? size / 1024 + "Mb" : size + "Kb";

            // Check preference presence
            String prefName = StorageHelper.getSharedPreferencesFile(getActivity()).getName();
            boolean hasPreferences = (new File(backupDir, prefName)).exists();

            String message = String.format("%s (%s %s)", backups.get(position), sizeString,
                hasPreferences ? getString(R.string.settings_included) : "");

            new MaterialAlertDialogBuilder(getActivity())
                .setTitle(R.string.confirm_restoring_backup)
                .setMessage(message)
                .setPositiveButton(R.string.confirm, (dialog1, which1) -> {
                  ((OmniNotes) getActivity().getApplication()).getAnalyticsHelper().trackEvent(
                      AnalyticsHelper.CATEGORIES.SETTING,
                      "settings_import_data");

                  // An IntentService will be launched to accomplish the import task
                  Intent service = new Intent(getActivity(),
                      DataBackupIntentService.class);
                  service.setAction(DataBackupIntentService.ACTION_DATA_IMPORT);
                  service.putExtra(DataBackupIntentService.INTENT_BACKUP_NAME,
                      backups.get(position));
                  getActivity().startService(service);
                }).show();
          })
          .setNegativeButton(R.string.delete, (dialog, which) -> {
            int position = ((AlertDialog) dialog).getListView().getCheckedItemPosition();
            File backupDir = StorageHelper.getOrCreateBackupDir(backups.get(position));
            long size = StorageHelper.getSize(backupDir) / 1024;
            String sizeString = size > 1024 ? size / 1024 + "Mb" : size + "Kb";

            new MaterialDialog.Builder(getActivity())
                .title(R.string.confirm_removing_backup)
                .content(backups.get(position) + "" + " (" + sizeString + ")")
                .positiveText(R.string.confirm)
                .onPositive((dialog12, which1) -> {
                  Intent service = new Intent(getActivity(),
                      DataBackupIntentService.class);
                  service.setAction(DataBackupIntentService.ACTION_DATA_DELETE);
                  service.putExtra(DataBackupIntentService.INTENT_BACKUP_NAME,
                      backups.get(position));
                  getActivity().startService(service);
                }).build().show();
          });

      importDialog.show();
    }
  }


  public void attemptOpenPgpTestSign(Intent data) {
    if (cachedOpenPgpKey != 0 && cachedServiceConnection != null) {
      data.setAction(OpenPgpApi.ACTION_DETACHED_SIGN);
      data.putExtra(OpenPgpApi.EXTRA_SIGN_KEY_ID, cachedOpenPgpKey);
      data.putExtra(OpenPgpApi.EXTRA_REQUEST_ASCII_ARMOR, false);
      
      byte[] bytes = new byte[42];
      Arrays.fill(bytes, (byte)(42));

      OpenPgpApi api = new OpenPgpApi(getContext(), cachedServiceConnection.getService());
      api.executeApiAsync(data, new ByteArrayInputStream(bytes), null, new MyOpenPgpCallback());
    }
  }


  private void finishOpenPgpTestSign(boolean showError) {
    cachedBackupName = null;
    cachedOpenPgpProvider = null;
    cachedOpenPgpKey = 0;
    cachedSignEncryptedBackups = false;
    if (cachedServiceConnection != null) {
      cachedServiceConnection.unbindFromService();
    }
    cachedServiceConnection = null;
    if (showError) {
      new MaterialDialog.Builder(getContext())
          .content(R.string.encrypted_backup_error)
          .positiveText(R.string.ok)
          .build().show();
    }
  }


  private void export(View v, String openPgpProvider, long openPgpKey, boolean signEncryptedBackups) {
    String[] backupsArray = StorageHelper.getOrCreateExternalStoragePublicDir().list();
    final List<String> backups = ArrayUtils.isEmpty(backupsArray) ? emptyList() : asList(backupsArray);

    // Sets default export file name
    SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT_EXPORT);
    String fileName = sdf.format(Calendar.getInstance().getTime());
    final EditText fileNameEditText = v.findViewById(R.id.export_file_name);
    final TextView backupExistingTextView = v.findViewById(R.id.backup_existing);
    boolean encrypt = (!TextUtils.isEmpty(openPgpProvider) && openPgpKey != 0);
    fileNameEditText.setHint((!encrypt) ? fileName : ("ON-" + fileName + ".zip.gpg"));
    fileNameEditText.addTextChangedListener(new TextWatcher() {
      @Override
      public void onTextChanged(CharSequence arg0, int arg1, int arg2, int arg3) {
        // Nothing to do
      }


      @Override
      public void beforeTextChanged(CharSequence arg0, int arg1, int arg2, int arg3) {
        // Nothing to do
      }


      @Override
      public void afterTextChanged(Editable arg0) {

        if (backups.contains(arg0.toString())) {
          backupExistingTextView.setText(R.string.backup_existing);
        } else {
          backupExistingTextView.setText("");
        }
      }
    });

    new MaterialAlertDialogBuilder(getContext())
        .setTitle(R.string.data_export_message)
        .setView(v)
        .setPositiveButton(R.string.confirm, (dialog, which) -> {
          ((OmniNotes) getActivity().getApplication()).getAnalyticsHelper().trackEvent(
              AnalyticsHelper.CATEGORIES.SETTING, "settings_export_data");
          String backupName = TextUtils.isEmpty(fileNameEditText.getText().toString()) ?
              fileNameEditText.getHint().toString() : fileNameEditText.getText().toString();
          if (encrypt) {
            cachedBackupName = backupName;
            cachedOpenPgpProvider = openPgpProvider;
            cachedOpenPgpKey = openPgpKey;
            cachedSignEncryptedBackups = signEncryptedBackups;
            cachedServiceConnection = new OpenPgpServiceConnection(
              getContext().getApplicationContext(),
              openPgpProvider,
              new OpenPgpServiceConnection.OnBound() {
                @Override
                public void onBound(IOpenPgpService2 service) {
                  attemptOpenPgpTestSign(new Intent());
                }

                @Override
                public void onError(Exception e) {
                  LogDelegate.e("OpenPgp exception on service binding (test sign): " + e);
                  finishOpenPgpTestSign(true);
                }
              }
            );
            cachedServiceConnection.bindToService();
          } else {
            BackupHelper.startBackupService(backupName, null, 0, false);
          }
        }).show();
  }


  @Override
  public void onStart() {
    ((OmniNotes) getActivity().getApplication()).getAnalyticsHelper()
        .trackScreenView(getClass().getName());
    super.onStart();
  }


  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent intent) {
    if (resultCode == Activity.RESULT_OK) {
      switch (requestCode) {
        case SPRINGPAD_IMPORT:
          Uri filesUri = intent.getData();
          String path = FileHelper.getPath(getActivity(), filesUri);
          // An IntentService will be launched to accomplish the import task
          Intent service = new Intent(getActivity(), DataBackupIntentService.class);
          service.setAction(SpringImportHelper.ACTION_DATA_IMPORT_SPRINGPAD);
          service.putExtra(SpringImportHelper.EXTRA_SPRINGPAD_BACKUP, path);
          getActivity().startService(service);
          break;

        case RINGTONE_REQUEST_CODE:
          Uri uri = intent.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
          String notificationSound = uri == null ? null : uri.toString();
          prefs.edit().putString("settings_notification_ringtone", notificationSound).apply();
          break;

        default:
          LogDelegate.e("Wrong element choosen: " + requestCode);
      }
    }
  }


  public void onOpenPgpKeySelected (int requestCode, int resultCode, Intent intent) {
    final OpenPgpKeyPreference openPgpKey = findPreference(PREF_OPENPGP_KEY);
    if (openPgpKey != null) {
      openPgpKey.handleOnActivityResult(requestCode, resultCode, intent);
    }
  }
}
