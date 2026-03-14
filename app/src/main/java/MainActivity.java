package com.hfm.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.net.Uri;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

// Google Drive Imports Added Here
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.api.services.drive.DriveScopes;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.json.JSONException;

import java.io.File;
import java.io.Serializable;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final String TAG = "HFM_MainActivity";
    private static final int STORAGE_PERMISSION_REQUEST_CODE = 456;
    private static final int NOTIFICATION_PERMISSION_REQUEST_CODE = 457;
    private static final int DROP_FILE_PICKER_REQUEST_CODE = 999;
    
    // New variable for Google Drive Request Code
    private static final int GOOGLE_DRIVE_SIGNIN_REQUEST_CODE = 1001;

    private WebView webView;
    private FirebaseAuth mAuth;
    private ArrayList<String> filesToSendViaDrop;

    // New variable for Google Drive
    private GoogleSignInClient googleSignInClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Launch the Dashboard Activity as a popup on start
        startActivity(new Intent(this, DashboardActivity.class));

        webView = findViewById(R.id.webView);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webSettings.setAllowContentAccess(true);
        webView.setBackgroundColor(0x00000000);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        webView.addJavascriptInterface(new WebAppInterface(this), "Android");

        mAuth = FirebaseAuth.getInstance();

        // Initialize Google Drive Auth
        setupGoogleDriveAuth();

        requestFilePermissions();
        signInAnonymously();
        webView.loadUrl("file:///android_asset/webview-app.html");
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // New Method for Google Drive
    private void setupGoogleDriveAuth() {
        GoogleSignInOptions gso = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(new Scope(DriveScopes.DRIVE_FILE))
                .build();
        googleSignInClient = GoogleSignIn.getClient(this, gso);
    }

    // New Method for Google Drive
    private void updateWebViewDriveStatus(final boolean isLoggedIn) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (webView != null) {
                    webView.evaluateJavascript("window.updateDriveAuthStatus(" + isLoggedIn + ");", null);
                }
            }
        });
    }

    private void signInAnonymously() {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            mAuth.signInAnonymously()
                .addOnCompleteListener(this, new OnCompleteListener<AuthResult>() {
                    @Override
                    public void onComplete(@NonNull Task<AuthResult> task) {
                        if (task.isSuccessful()) {
                            Log.d(TAG, "signInAnonymously:success");
                        } else {
                            Log.w(TAG, "signInAnonymously:failure", task.getException());
                            Toast.makeText(MainActivity.this, "Anonymous authentication failed.", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
        } else {
            Log.d(TAG, "User already signed in anonymously with UID: " + currentUser.getUid());
        }
    }

    private void requestFilePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { // Android 11+
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.addCategory("android.intent.category.DEFAULT");
                    intent.setData(Uri.parse(String.format("package:%s", getApplicationContext().getPackageName())));
                    startActivityForResult(intent, STORAGE_PERMISSION_REQUEST_CODE);
                } catch (Exception e) {
                    Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(intent, STORAGE_PERMISSION_REQUEST_CODE);
                }
            } else {
                requestNotificationPermission();
            }
        } else { // Android 10 and below
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_REQUEST_CODE);
            } else {
                requestNotificationPermission();
            }
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST_CODE);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    requestNotificationPermission();
                } else {
                    Toast.makeText(this, "All Files Access permission is required for the app to function correctly.", Toast.LENGTH_LONG).show();
                }
            }
        } else if (requestCode == DROP_FILE_PICKER_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            if (data != null && data.hasExtra("picked_files")) {
                filesToSendViaDrop = data.getStringArrayListExtra("picked_files");
                if (filesToSendViaDrop != null && !filesToSendViaDrop.isEmpty()) {
                    showSendToDropDialog();
                }
            }
        } 
        // --- NEW GOOGLE DRIVE RESULT LOGIC ADDED HERE ---
        else if (requestCode == GOOGLE_DRIVE_SIGNIN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Task<GoogleSignInAccount> task = GoogleSignIn.getSignedInAccountFromIntent(data);
                try {
                    GoogleSignInAccount account = task.getResult(ApiException.class);
                    if (account != null && GoogleSignIn.hasPermissions(account, new Scope(DriveScopes.DRIVE_FILE))) {
                        Toast.makeText(this, "Google Drive Connected!", Toast.LENGTH_SHORT).show();
                        updateWebViewDriveStatus(true);
                    } else {
                        GoogleSignIn.requestPermissions(this, GOOGLE_DRIVE_SIGNIN_REQUEST_CODE, account, new Scope(DriveScopes.DRIVE_FILE));
                    }
                } catch (ApiException e) {
                    Log.e(TAG, "Google Sign-In failed", e);
                    Toast.makeText(this, "Google Drive Sign-In Failed.", Toast.LENGTH_SHORT).show();
                    updateWebViewDriveStatus(false);
                }
            } else {
                Toast.makeText(this, "Google Drive Sign-In Cancelled.", Toast.LENGTH_SHORT).show();
                updateWebViewDriveStatus(false);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == STORAGE_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermission();
            } else {
                Toast.makeText(this, "Storage permission is required for the app to function.", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notifications will be disabled.", Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    private String generateSecretNumber() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void showSendToDropDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_send_drop, null);
        final EditText receiverUsernameInput = dialogView.findViewById(R.id.edit_text_receiver_username);

        builder.setView(dialogView)
                .setPositiveButton("Send", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        String receiverUsername = receiverUsernameInput.getText().toString().trim();
                        if (receiverUsername.isEmpty()) {
                            Toast.makeText(MainActivity.this, "Receiver username cannot be empty.", Toast.LENGTH_SHORT).show();
                        } else {
                            showSenderWarningDialog(receiverUsername);
                        }
                    }
                })
                .setNegativeButton("Cancel", null);
        builder.create().show();
    }
    
    private void showSenderWarningDialog(final String receiverUsername) {
        final String secretNumber = generateSecretNumber();

        new AlertDialog.Builder(this)
            .setTitle("Important: Connection Stability")
            .setMessage("You are about to act as a temporary server for this file transfer.\n\n"
                    + "Please keep the app open and maintain a stable internet connection until the transfer is complete.\n\n"
                    + "Your Secret Number for this transfer is:\n" + secretNumber + "\n\nShare this number with the receiver.")
            .setPositiveButton("I Understand, Start Sending", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    startSenderService(receiverUsername, secretNumber);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void startSenderService(String receiverUsername, String secretNumber) {
        if (filesToSendViaDrop == null || filesToSendViaDrop.isEmpty()) {
            Toast.makeText(this, "Error: No file selected to send.", Toast.LENGTH_SHORT).show();
            return;
        }
        // SenderService will only handle one file at a time per the plan.
        String filePath = filesToSendViaDrop.get(0);

        Intent intent = new Intent(this, SenderService.class);
        intent.setAction(SenderService.ACTION_START_SEND);
        intent.putExtra(SenderService.EXTRA_FILE_PATH, filePath);
        intent.putExtra(SenderService.EXTRA_RECEIVER_USERNAME, receiverUsername);
        intent.putExtra(SenderService.EXTRA_SECRET_NUMBER, secretNumber);
        ContextCompat.startForegroundService(this, intent);

        filesToSendViaDrop = null;
    }


    public class WebAppInterface {
        Context mContext;

        WebAppInterface(Context c) {
            mContext = c;
        }

        @JavascriptInterface
        public void log(String message) {
            Log.d("HFMApp_WebView", message);
        }

        @JavascriptInterface
        public void openDashboard() {
            Log.d("HFMApp_WebView", "openDashboard() called. Relaunching DashboardActivity.");
            Intent intent = new Intent(mContext, DashboardActivity.class);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void openSearch() {
            Log.d("HFMApp_WebView", "openSearch() called. Launching SearchActivity.");
            Intent intent = new Intent(mContext, SearchActivity.class);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void openMassDelete() {
            Log.d("HFMApp_WebView", "openMassDelete() called. Launching MassDeleteActivity.");
            Intent intent = new Intent(mContext, MassDeleteActivity.class);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void openRecycleBin() {
            Log.d("HFMApp_WebView", "openRecycleBin() called. Launching RecycleBinActivity.");
            Intent intent = new Intent(mContext, RecycleBinActivity.class);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void openContactForm() {
            Log.d("HFMApp_WebView", "openContactForm() called. Launching ContactActivity.");
            Intent intent = new Intent(mContext, ContactActivity.class);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void clearCache() {
            Log.d("HFMApp_WebView", "clearCache() called. Launching CacheCleanerActivity.");
            Intent intent = new Intent(mContext, CacheCleanerActivity.class);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void openReader() {
            Log.d("HFMApp_WebView", "openReader() called. Launching ReaderActivity.");
            Intent intent = new Intent(mContext, ReaderActivity.class);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void openStorageMap() {
            Log.d("HFMApp_WebView", "openStorageMap() called. Launching StorageMapActivity.");
            Intent intent = new Intent(mContext, StorageMapActivity.class);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void onHideIconTapped() {
            Log.d(TAG, "Hide icon tapped. Checking for existing rituals...");
            RitualManager ritualManager = new RitualManager();
            List<RitualManager.Ritual> rituals = ritualManager.loadRituals(mContext);

            if (rituals == null || rituals.isEmpty()) {
                Log.d(TAG, "No rituals found. Launching FileHiderActivity.");
                Intent intent = new Intent(mContext, FileHiderActivity.class);
                mContext.startActivity(intent);
            } else {
                Log.d(TAG, rituals.size() + " rituals found. Launching RitualListActivity.");
                Intent intent = new Intent(mContext, RitualListActivity.class);
                mContext.startActivity(intent);
            }
        }

        @JavascriptInterface
        public void setTheme(final String themeName) {
            Log.d(TAG, "setTheme() called from WebView with theme: " + themeName);
            ThemeManager.setTheme(mContext, themeName);
            new android.os.Handler(mContext.getMainLooper()).post(new Runnable() {
					@Override
					public void run() {
						Toast.makeText(mContext, "Theme changed. Please restart the app to see the full effect.", Toast.LENGTH_LONG).show();
					}
				});
        }

        @JavascriptInterface
        public void openShareHub() {
            Log.d("HFMApp_WebView", "openShareHub() called. Launching ShareHubActivity.");
            Intent intent = new Intent(mContext, ShareHubActivity.class);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void openApiKeyDialog() {
            runOnUiThread(new Runnable() {
					@Override
					public void run() {
						AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
						LayoutInflater inflater = (LayoutInflater) mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
						View dialogView = inflater.inflate(R.layout.dialog_api_key, null);
						final EditText apiKeyInput = dialogView.findViewById(R.id.edit_text_api_key);

						String currentKey = ApiKeyManager.getApiKey(mContext);
						if (currentKey != null) {
							apiKeyInput.setText(currentKey);
						}

						builder.setView(dialogView)
							.setPositiveButton("Save", new DialogInterface.OnClickListener() {
								@Override
								public void onClick(DialogInterface dialog, int id) {
									String newKey = apiKeyInput.getText().toString().trim();
									ApiKeyManager.saveApiKey(mContext, newKey);
									if (newKey.isEmpty()) {
										Toast.makeText(mContext, "API Key cleared.", Toast.LENGTH_SHORT).show();
									} else {
										Toast.makeText(mContext, "API Key saved.", Toast.LENGTH_SHORT).show();
									}
								}
							})
							.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
								public void onClick(DialogInterface dialog, int id) {
									dialog.cancel();
								}
							});
						builder.create().show();
					}
				});
        }

        // --- NEW JAVASCRIPT INTERFACE METHODS FOR GOOGLE DRIVE ---
        
        @JavascriptInterface
        public boolean isDriveLoggedIn() {
            GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(mContext);
            return account != null && GoogleSignIn.hasPermissions(account, new Scope(DriveScopes.DRIVE_FILE));
        }

        @JavascriptInterface
        public void toggleDriveAuth() {
            if (isDriveLoggedIn()) {
                // Log Out
                googleSignInClient.signOut().addOnCompleteListener(new OnCompleteListener<Void>() {
                    @Override
                    public void onComplete(@NonNull Task<Void> task) {
                        Toast.makeText(mContext, "Logged out of Google Drive", Toast.LENGTH_SHORT).show();
                        updateWebViewDriveStatus(false);
                    }
                });
            } else {
                // Log In using classic startActivityForResult
                Intent signInIntent = googleSignInClient.getSignInIntent();
                startActivityForResult(signInIntent, GOOGLE_DRIVE_SIGNIN_REQUEST_CODE);
            }
        }

        @JavascriptInterface
        public void openDriveViewer() {
            if (isDriveLoggedIn()) {
                Intent intent = new Intent(mContext, DriveViewerActivity.class);
                mContext.startActivity(intent);
            } else {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(mContext, "Please sign in to Google Drive first.", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }

        // --- JAVASCRIPT INTERFACE METHODS FOR HFM MESSENGER DROP ---

        @JavascriptInterface
        public void sendViaDrop() {
            Log.d("HFMApp_WebView", "sendViaDrop() called.");
            Intent intent = new Intent(mContext, CategoryPickerActivity.class);
            startActivityForResult(intent, DROP_FILE_PICKER_REQUEST_CODE);
        }

        @JavascriptInterface
        public void receiveViaDrop() {
            Log.d("HFMApp_WebView", "receiveViaDrop() called.");
            Intent intent = new Intent(mContext, HFMDropActivity.class);
            mContext.startActivity(intent);
        }

        @JavascriptInterface
        public void regenerateHFMId() {
            Log.d("HFMApp_WebView", "regenerateHFMId() called.");
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    new AlertDialog.Builder(mContext)
                        .setTitle("Regenerate HFM ID")
                        .setMessage("Are you sure? This will permanently delete your current anonymous ID. This action cannot be undone and may disrupt pending transfers.")
                        .setPositiveButton("Regenerate", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                final FirebaseUser user = mAuth.getCurrentUser();
                                if (user != null) {
                                    user.delete().addOnCompleteListener(new OnCompleteListener<Void>() {
                                        @Override
                                        public void onComplete(@NonNull Task<Void> task) {
                                            if (task.isSuccessful()) {
                                                Toast.makeText(mContext, "ID regenerated.", Toast.LENGTH_SHORT).show();
                                                signInAnonymously();
                                            } else {
                                                Toast.makeText(mContext, "Failed to regenerate ID.", Toast.LENGTH_SHORT).show();
                                            }
                                        }
                                    });
                                }
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                }
            });
        }
    }
}