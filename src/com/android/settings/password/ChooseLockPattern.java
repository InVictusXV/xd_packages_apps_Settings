/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings.password;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Resources.Theme;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.widget.LinearLayoutWithDefaultTouchRecepient;
import com.android.internal.widget.LockPatternUtils;
import com.android.internal.widget.LockPatternUtils.RequestThrottledException;
import com.android.internal.widget.LockPatternView;
import com.android.internal.widget.LockPatternView.Cell;
import com.android.internal.widget.LockPatternView.DisplayMode;
import com.android.settings.EncryptionInterstitial;
import com.android.settings.R;
import com.android.settings.SettingsActivity;
import com.android.settings.SetupWizardUtils;
import com.android.settings.Utils;
import com.android.settings.core.InstrumentedFragment;
import com.android.settings.notification.RedactionInterstitial;
import com.android.setupwizardlib.GlifLayout;

import com.google.android.collect.Lists;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import androidx.fragment.app.Fragment;

/**
 * If the user has a lock pattern set already, makes them confirm the existing one.
 *
 * Then, prompts the user to choose a lock pattern:
 * - prompts for initial pattern
 * - asks for confirmation / restart
 * - saves chosen password when confirmed
 */
public class ChooseLockPattern extends SettingsActivity {
    /**
     * Used by the choose lock pattern wizard to indicate the wizard is
     * finished, and each activity in the wizard should finish.
     * <p>
     * Previously, each activity in the wizard would finish itself after
     * starting the next activity. However, this leads to broken 'Back'
     * behavior. So, now an activity does not finish itself until it gets this
     * result.
     */
    static final int RESULT_FINISHED = RESULT_FIRST_USER;

    private static final String TAG = "ChooseLockPattern";

    @Override
    public Intent getIntent() {
        Intent modIntent = new Intent(super.getIntent());
        modIntent.putExtra(EXTRA_SHOW_FRAGMENT, getFragmentClass().getName());
        return modIntent;
    }

    @Override
    protected void onApplyThemeResource(Theme theme, int resid, boolean first) {
        resid = SetupWizardUtils.getTheme(getIntent());
        super.onApplyThemeResource(theme, resid, first);
    }

    public static class IntentBuilder {
        private final Intent mIntent;

        public IntentBuilder(Context context) {
            mIntent = new Intent(context, ChooseLockPattern.class);
            mIntent.putExtra(EncryptionInterstitial.EXTRA_REQUIRE_PASSWORD, false);
            mIntent.putExtra(ChooseLockGeneric.CONFIRM_CREDENTIALS, false);
            mIntent.putExtra(ChooseLockSettingsHelper.EXTRA_KEY_HAS_CHALLENGE, false);
        }

        public IntentBuilder setUserId(int userId) {
            mIntent.putExtra(Intent.EXTRA_USER_ID, userId);
            return this;
        }

        public IntentBuilder setChallenge(long challenge) {
            mIntent.putExtra(ChooseLockSettingsHelper.EXTRA_KEY_HAS_CHALLENGE, true);
            mIntent.putExtra(ChooseLockSettingsHelper.EXTRA_KEY_CHALLENGE, challenge);
            return this;
        }

        public IntentBuilder setPattern(String pattern) {
            mIntent.putExtra(ChooseLockSettingsHelper.EXTRA_KEY_PASSWORD, pattern);
            return this;
        }

        public IntentBuilder setForFingerprint(boolean forFingerprint) {
            mIntent.putExtra(ChooseLockSettingsHelper.EXTRA_KEY_FOR_FINGERPRINT, forFingerprint);
            return this;
        }

        public IntentBuilder setForFace(boolean forFace) {
            mIntent.putExtra(ChooseLockSettingsHelper.EXTRA_KEY_FOR_FACE, forFace);
            return this;
        }

        public Intent build() {
            return mIntent;
        }
    }

    @Override
    protected boolean isValidFragment(String fragmentName) {
        if (ChooseLockPatternFragment.class.getName().equals(fragmentName)) return true;
        return false;
    }

    /* package */ Class<? extends Fragment> getFragmentClass() {
        return ChooseLockPatternFragment.class;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        final boolean forFingerprint = getIntent()
                .getBooleanExtra(ChooseLockSettingsHelper.EXTRA_KEY_FOR_FINGERPRINT, false);
        final boolean forFace = getIntent()
                .getBooleanExtra(ChooseLockSettingsHelper.EXTRA_KEY_FOR_FACE, false);

        int msg = R.string.lockpassword_choose_your_screen_lock_header;
        if (forFingerprint) {
            msg = R.string.lockpassword_choose_your_pattern_header_for_fingerprint;
        } else if (forFace) {
            msg = R.string.lockpassword_choose_your_pattern_header_for_face;
        }

        setTitle(msg);
        LinearLayout layout = (LinearLayout) findViewById(R.id.content_parent);
        layout.setFitsSystemWindows(false);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // *** TODO ***
        // chooseLockPatternFragment.onKeyDown(keyCode, event);
        return super.onKeyDown(keyCode, event);
    }

    public static class ChooseLockPatternFragment extends InstrumentedFragment
            implements View.OnClickListener, SaveAndFinishWorker.Listener {

        public static final int CONFIRM_EXISTING_REQUEST = 55;

        // how long after a confirmation message is shown before moving on
        static final int INFORMATION_MSG_TIMEOUT_MS = 3000;

        // how long we wait to clear a wrong pattern
        private static final int WRONG_PATTERN_CLEAR_TIMEOUT_MS = 2000;

        private static final int ID_EMPTY_MESSAGE = -1;

        private static final String FRAGMENT_TAG_SAVE_AND_FINISH = "save_and_finish_worker";

        private String mCurrentPattern;
        private boolean mHasChallenge;
        private long mChallenge;
        protected TextView mTitleText;
        protected TextView mHeaderText;
        protected TextView mMessageText;
        protected LockPatternView mLockPatternView;
        protected TextView mFooterText;
        private TextView mFooterLeftButton;
        private TextView mFooterRightButton;
        protected List<LockPatternView.Cell> mChosenPattern = null;
        private boolean mHideDrawer = false;
        private ColorStateList mDefaultHeaderColorList;

        // ScrollView that contains title and header, only exist in land mode
        private ScrollView mTitleHeaderScrollView;

        /**
         * The patten used during the help screen to show how to draw a pattern.
         */
        private final List<LockPatternView.Cell> mAnimatePattern =
                Collections.unmodifiableList(Lists.newArrayList(
                        LockPatternView.Cell.of(0, 0),
                        LockPatternView.Cell.of(0, 1),
                        LockPatternView.Cell.of(1, 1),
                        LockPatternView.Cell.of(2, 1)
                ));

        @Override
        public void onActivityResult(int requestCode, int resultCode,
                Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            switch (requestCode) {
                case CONFIRM_EXISTING_REQUEST:
                    if (resultCode != Activity.RESULT_OK) {
                        getActivity().setResult(RESULT_FINISHED);
                        getActivity().finish();
                    } else {
                        mCurrentPattern = data.getStringExtra(
                                ChooseLockSettingsHelper.EXTRA_KEY_PASSWORD);
                    }

                    updateStage(Stage.Introduction);
                    break;
            }
        }

        protected void setRightButtonEnabled(boolean enabled) {
            mFooterRightButton.setEnabled(enabled);
        }

        protected void setRightButtonText(int text) {
            mFooterRightButton.setText(text);
        }

        /**
         * The pattern listener that responds according to a user choosing a new
         * lock pattern.
         */
        protected LockPatternView.OnPatternListener mChooseNewLockPatternListener =
                new LockPatternView.OnPatternListener() {

                public void onPatternStart() {
                    mLockPatternView.removeCallbacks(mClearPatternRunnable);
                    patternInProgress();
                }

                public void onPatternCleared() {
                    mLockPatternView.removeCallbacks(mClearPatternRunnable);
                }

                public void onPatternDetected(List<LockPatternView.Cell> pattern) {
                    if (mUiStage == Stage.NeedToConfirm || mUiStage == Stage.ConfirmWrong) {
                        if (mChosenPattern == null) throw new IllegalStateException(
                                "null chosen pattern in stage 'need to confirm");
                        if (mChosenPattern.equals(pattern)) {
                            updateStage(Stage.ChoiceConfirmed);
                        } else {
                            updateStage(Stage.ConfirmWrong);
                        }
                    } else if (mUiStage == Stage.Introduction || mUiStage == Stage.ChoiceTooShort){
                        if (pattern.size() < LockPatternUtils.MIN_LOCK_PATTERN_SIZE) {
                            updateStage(Stage.ChoiceTooShort);
                        } else {
                            mChosenPattern = new ArrayList<LockPatternView.Cell>(pattern);
                            updateStage(Stage.FirstChoiceValid);
                        }
                    } else {
                        throw new IllegalStateException("Unexpected stage " + mUiStage + " when "
                                + "entering the pattern.");
                    }
                }

                public void onPatternCellAdded(List<Cell> pattern) {

                }

                private void patternInProgress() {
                    mHeaderText.setText(R.string.lockpattern_recording_inprogress);
                    if (mDefaultHeaderColorList != null) {
                        mHeaderText.setTextColor(mDefaultHeaderColorList);
                    }
                    mFooterText.setText("");
                    mFooterLeftButton.setEnabled(false);
                    mFooterRightButton.setEnabled(false);

                    if (mTitleHeaderScrollView != null) {
                        mTitleHeaderScrollView.post(new Runnable() {
                            @Override
                            public void run() {
                                mTitleHeaderScrollView.fullScroll(ScrollView.FOCUS_DOWN);
                            }
                        });
                    }
                }
         };

        @Override
        public int getMetricsCategory() {
            return MetricsEvent.CHOOSE_LOCK_PATTERN;
        }


        /**
         * The states of the left footer button.
         */
        enum LeftButtonMode {
            Retry(R.string.lockpattern_retry_button_text, true),
            RetryDisabled(R.string.lockpattern_retry_button_text, false),
            Gone(ID_EMPTY_MESSAGE, false);


            /**
             * @param text The displayed text for this mode.
             * @param enabled Whether the button should be enabled.
             */
            LeftButtonMode(int text, boolean enabled) {
                this.text = text;
                this.enabled = enabled;
            }

            final int text;
            final boolean enabled;
        }

        /**
         * The states of the right button.
         */
        enum RightButtonMode {
            Continue(R.string.next_label, true),
            ContinueDisabled(R.string.next_label, false),
            Confirm(R.string.lockpattern_confirm_button_text, true),
            ConfirmDisabled(R.string.lockpattern_confirm_button_text, false),
            Ok(android.R.string.ok, true);

            /**
             * @param text The displayed text for this mode.
             * @param enabled Whether the button should be enabled.
             */
            RightButtonMode(int text, boolean enabled) {
                this.text = text;
                this.enabled = enabled;
            }

            final int text;
            final boolean enabled;
        }

        /**
         * Keep track internally of where the user is in choosing a pattern.
         */
        protected enum Stage {

            Introduction(
                    R.string.lock_settings_picker_biometrics_added_security_message,
                    R.string.lockpassword_choose_your_pattern_message,
                    R.string.lockpattern_recording_intro_header,
                    LeftButtonMode.Gone, RightButtonMode.ContinueDisabled,
                    ID_EMPTY_MESSAGE, true),
            HelpScreen(
                    ID_EMPTY_MESSAGE, ID_EMPTY_MESSAGE, R.string.lockpattern_settings_help_how_to_record,
                    LeftButtonMode.Gone, RightButtonMode.Ok, ID_EMPTY_MESSAGE, false),
            ChoiceTooShort(
                    R.string.lock_settings_picker_biometrics_added_security_message,
                    R.string.lockpassword_choose_your_pattern_message,
                    R.string.lockpattern_recording_incorrect_too_short,
                    LeftButtonMode.Retry, RightButtonMode.ContinueDisabled,
                    ID_EMPTY_MESSAGE, true),
            FirstChoiceValid(
                    R.string.lock_settings_picker_biometrics_added_security_message,
                    R.string.lockpassword_choose_your_pattern_message,
                    R.string.lockpattern_pattern_entered_header,
                    LeftButtonMode.Retry, RightButtonMode.Continue, ID_EMPTY_MESSAGE, false),
            NeedToConfirm(
                    ID_EMPTY_MESSAGE, ID_EMPTY_MESSAGE, R.string.lockpattern_need_to_confirm,
                    LeftButtonMode.Gone, RightButtonMode.ConfirmDisabled,
                    ID_EMPTY_MESSAGE, true),
            ConfirmWrong(
                    ID_EMPTY_MESSAGE, ID_EMPTY_MESSAGE, R.string.lockpattern_need_to_unlock_wrong,
                    LeftButtonMode.Gone, RightButtonMode.ConfirmDisabled,
                    ID_EMPTY_MESSAGE, true),
            ChoiceConfirmed(
                    ID_EMPTY_MESSAGE, ID_EMPTY_MESSAGE, R.string.lockpattern_pattern_confirmed_header,
                    LeftButtonMode.Gone, RightButtonMode.Confirm, ID_EMPTY_MESSAGE, false);


            /**
             * @param messageForBiometrics The message displayed at the top, above header for
             *                              fingerprint flow.
             * @param message The message displayed at the top.
             * @param headerMessage The message displayed at the top.
             * @param leftMode The mode of the left button.
             * @param rightMode The mode of the right button.
             * @param footerMessage The footer message.
             * @param patternEnabled Whether the pattern widget is enabled.
             */
            Stage(int messageForBiometrics, int message, int headerMessage,
                    LeftButtonMode leftMode,
                    RightButtonMode rightMode,
                    int footerMessage, boolean patternEnabled) {
                this.headerMessage = headerMessage;
                this.messageForBiometrics = messageForBiometrics;
                this.message = message;
                this.leftMode = leftMode;
                this.rightMode = rightMode;
                this.footerMessage = footerMessage;
                this.patternEnabled = patternEnabled;
            }

            final int headerMessage;
            final int messageForBiometrics;
            final int message;
            final LeftButtonMode leftMode;
            final RightButtonMode rightMode;
            final int footerMessage;
            final boolean patternEnabled;
        }

        private Stage mUiStage = Stage.Introduction;

        private Runnable mClearPatternRunnable = new Runnable() {
            public void run() {
                mLockPatternView.clearPattern();
            }
        };

        private ChooseLockSettingsHelper mChooseLockSettingsHelper;
        private SaveAndFinishWorker mSaveAndFinishWorker;
        protected int mUserId;
        protected boolean mForFingerprint;
        protected boolean mForFace;

        private static final String KEY_UI_STAGE = "uiStage";
        private static final String KEY_PATTERN_CHOICE = "chosenPattern";
        private static final String KEY_CURRENT_PATTERN = "currentPattern";

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            mChooseLockSettingsHelper = new ChooseLockSettingsHelper(getActivity());
            if (!(getActivity() instanceof ChooseLockPattern)) {
                throw new SecurityException("Fragment contained in wrong activity");
            }
            Intent intent = getActivity().getIntent();
            // Only take this argument into account if it belongs to the current profile.
            mUserId = Utils.getUserIdFromBundle(getActivity(), intent.getExtras());

            if (intent.getBooleanExtra(
                    ChooseLockSettingsHelper.EXTRA_KEY_FOR_CHANGE_CRED_REQUIRED_FOR_BOOT, false)) {
                SaveAndFinishWorker w = new SaveAndFinishWorker();
                final boolean required = getActivity().getIntent().getBooleanExtra(
                        EncryptionInterstitial.EXTRA_REQUIRE_PASSWORD, true);
                String current = intent.getStringExtra(
                        ChooseLockSettingsHelper.EXTRA_KEY_PASSWORD);
                w.setBlocking(true);
                w.setListener(this);
                w.start(mChooseLockSettingsHelper.utils(), required,
                        false, 0, LockPatternUtils.stringToPattern(current), current, mUserId);
            }
            mHideDrawer = getActivity().getIntent().getBooleanExtra(EXTRA_HIDE_DRAWER, false);
            mForFingerprint = intent.getBooleanExtra(
                    ChooseLockSettingsHelper.EXTRA_KEY_FOR_FINGERPRINT, false);
            mForFace = intent.getBooleanExtra(
                    ChooseLockSettingsHelper.EXTRA_KEY_FOR_FACE, false);
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container,
                Bundle savedInstanceState) {
            final GlifLayout layout = (GlifLayout) inflater.inflate(
                    R.layout.choose_lock_pattern, container, false);
            layout.setHeaderText(getActivity().getTitle());
            if (getResources().getBoolean(R.bool.config_lock_pattern_minimal_ui)) {
                View iconView = layout.findViewById(R.id.suw_layout_icon);
                if (iconView != null) {
                    iconView.setVisibility(View.GONE);
                }
            } else {
                if (mForFingerprint) {
                    layout.setIcon(getActivity().getDrawable(R.drawable.ic_fingerprint_header));
                } else if (mForFace) {
                    layout.setIcon(getActivity().getDrawable(R.drawable.ic_face_header));
                }
            }
            return layout;
        }


        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            mTitleText = view.findViewById(R.id.suw_layout_title);
            mHeaderText = (TextView) view.findViewById(R.id.headerText);
            mDefaultHeaderColorList = mHeaderText.getTextColors();
            mMessageText = view.findViewById(R.id.message);
            mLockPatternView = (LockPatternView) view.findViewById(R.id.lockPattern);
            mLockPatternView.setOnPatternListener(mChooseNewLockPatternListener);
            mLockPatternView.setTactileFeedbackEnabled(
                    mChooseLockSettingsHelper.utils().isTactileFeedbackEnabled());
            mLockPatternView.setFadePattern(false);

            mFooterText = (TextView) view.findViewById(R.id.footerText);

            mFooterLeftButton = (TextView) view.findViewById(R.id.footerLeftButton);
            mFooterRightButton = (TextView) view.findViewById(R.id.footerRightButton);

            mTitleHeaderScrollView = (ScrollView) view.findViewById(R.id
                    .scroll_layout_title_header);

            mFooterLeftButton.setOnClickListener(this);
            mFooterRightButton.setOnClickListener(this);

            // make it so unhandled touch events within the unlock screen go to the
            // lock pattern view.
            final LinearLayoutWithDefaultTouchRecepient topLayout
                    = (LinearLayoutWithDefaultTouchRecepient) view.findViewById(
                    R.id.topLayout);
            topLayout.setDefaultTouchRecepient(mLockPatternView);

            final boolean confirmCredentials = getActivity().getIntent()
                    .getBooleanExtra(ChooseLockGeneric.CONFIRM_CREDENTIALS, true);
            Intent intent = getActivity().getIntent();
            mCurrentPattern = intent.getStringExtra(ChooseLockSettingsHelper.EXTRA_KEY_PASSWORD);
            mHasChallenge = intent.getBooleanExtra(
                    ChooseLockSettingsHelper.EXTRA_KEY_HAS_CHALLENGE, false);
            mChallenge = intent.getLongExtra(ChooseLockSettingsHelper.EXTRA_KEY_CHALLENGE, 0);

            if (savedInstanceState == null) {
                if (confirmCredentials) {
                    // first launch. As a security measure, we're in NeedToConfirm mode until we
                    // know there isn't an existing password or the user confirms their password.
                    updateStage(Stage.NeedToConfirm);
                    boolean launchedConfirmationActivity =
                        mChooseLockSettingsHelper.launchConfirmationActivity(
                                CONFIRM_EXISTING_REQUEST,
                                getString(R.string.unlock_set_unlock_launch_picker_title), true,
                                mUserId);
                    if (!launchedConfirmationActivity) {
                        updateStage(Stage.Introduction);
                    }
                } else {
                    updateStage(Stage.Introduction);
                }
            } else {
                // restore from previous state
                final String patternString = savedInstanceState.getString(KEY_PATTERN_CHOICE);
                if (patternString != null) {
                    mChosenPattern = LockPatternUtils.stringToPattern(patternString);
                }

                if (mCurrentPattern == null) {
                    mCurrentPattern = savedInstanceState.getString(KEY_CURRENT_PATTERN);
                }
                updateStage(Stage.values()[savedInstanceState.getInt(KEY_UI_STAGE)]);

                // Re-attach to the exiting worker if there is one.
                mSaveAndFinishWorker = (SaveAndFinishWorker) getFragmentManager().findFragmentByTag(
                        FRAGMENT_TAG_SAVE_AND_FINISH);
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            updateStage(mUiStage);

            if (mSaveAndFinishWorker != null) {
                setRightButtonEnabled(false);
                mSaveAndFinishWorker.setListener(this);
            }
        }

        @Override
        public void onPause() {
            super.onPause();
            if (mSaveAndFinishWorker != null) {
                mSaveAndFinishWorker.setListener(null);
            }
        }

        protected Intent getRedactionInterstitialIntent(Context context) {
            return RedactionInterstitial.createStartIntent(context, mUserId);
        }

        public void handleLeftButton() {
            if (mUiStage.leftMode == LeftButtonMode.Retry) {
                mChosenPattern = null;
                mLockPatternView.clearPattern();
                updateStage(Stage.Introduction);
            } else {
                throw new IllegalStateException("left footer button pressed, but stage of " +
                        mUiStage + " doesn't make sense");
            }
        }

        public void handleRightButton() {
            if (mUiStage.rightMode == RightButtonMode.Continue) {
                if (mUiStage != Stage.FirstChoiceValid) {
                    throw new IllegalStateException("expected ui stage "
                            + Stage.FirstChoiceValid + " when button is "
                            + RightButtonMode.Continue);
                }
                updateStage(Stage.NeedToConfirm);
            } else if (mUiStage.rightMode == RightButtonMode.Confirm) {
                if (mUiStage != Stage.ChoiceConfirmed) {
                    throw new IllegalStateException("expected ui stage " + Stage.ChoiceConfirmed
                            + " when button is " + RightButtonMode.Confirm);
                }
                startSaveAndFinish();
            } else if (mUiStage.rightMode == RightButtonMode.Ok) {
                if (mUiStage != Stage.HelpScreen) {
                    throw new IllegalStateException("Help screen is only mode with ok button, "
                            + "but stage is " + mUiStage);
                }
                mLockPatternView.clearPattern();
                mLockPatternView.setDisplayMode(DisplayMode.Correct);
                updateStage(Stage.Introduction);
            }
        }

        public void onClick(View v) {
            if (v == mFooterLeftButton) {
                handleLeftButton();
            } else if (v == mFooterRightButton) {
                handleRightButton();
            }
        }

        public boolean onKeyDown(int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
                if (mUiStage == Stage.HelpScreen) {
                    updateStage(Stage.Introduction);
                    return true;
                }
            }
            if (keyCode == KeyEvent.KEYCODE_MENU && mUiStage == Stage.Introduction) {
                updateStage(Stage.HelpScreen);
                return true;
            }
            return false;
        }

        public void onSaveInstanceState(Bundle outState) {
            super.onSaveInstanceState(outState);

            outState.putInt(KEY_UI_STAGE, mUiStage.ordinal());
            if (mChosenPattern != null) {
                outState.putString(KEY_PATTERN_CHOICE,
                        LockPatternUtils.patternToString(mChosenPattern));
            }

            if (mCurrentPattern != null) {
                outState.putString(KEY_CURRENT_PATTERN,
                        mCurrentPattern);
            }
        }

        /**
         * Updates the messages and buttons appropriate to what stage the user
         * is at in choosing a view.  This doesn't handle clearing out the pattern;
         * the pattern is expected to be in the right state.
         * @param stage
         */
        protected void updateStage(Stage stage) {
            final Stage previousStage = mUiStage;

            mUiStage = stage;

            // header text, footer text, visibility and
            // enabled state all known from the stage
            if (stage == Stage.ChoiceTooShort) {
                mHeaderText.setText(
                        getResources().getString(
                                stage.headerMessage,
                                LockPatternUtils.MIN_LOCK_PATTERN_SIZE));
            } else {
                mHeaderText.setText(stage.headerMessage);
            }
            final boolean forBiometrics = mForFingerprint || mForFace;
            int message = forBiometrics ? stage.messageForBiometrics : stage.message;
            if (message == ID_EMPTY_MESSAGE) {
                mMessageText.setText("");
            } else {
                mMessageText.setText(message);
            }
            if (stage.footerMessage == ID_EMPTY_MESSAGE) {
                mFooterText.setText("");
            } else {
                mFooterText.setText(stage.footerMessage);
            }

            if (stage == Stage.ConfirmWrong || stage == Stage.ChoiceTooShort) {
                TypedValue typedValue = new TypedValue();
                Theme theme = getActivity().getTheme();
                theme.resolveAttribute(R.attr.colorError, typedValue, true);
                mHeaderText.setTextColor(typedValue.data);

            } else {
                if (mDefaultHeaderColorList != null) {
                    mHeaderText.setTextColor(mDefaultHeaderColorList);
                }

                if (stage == Stage.NeedToConfirm && forBiometrics) {
                    mHeaderText.setText("");
                    mTitleText.setText(R.string.lockpassword_draw_your_pattern_again_header);
                }
            }

            updateFooterLeftButton(stage, mFooterLeftButton);

            setRightButtonText(stage.rightMode.text);
            setRightButtonEnabled(stage.rightMode.enabled);

            // same for whether the pattern is enabled
            if (stage.patternEnabled) {
                mLockPatternView.enableInput();
            } else {
                mLockPatternView.disableInput();
            }

            // the rest of the stuff varies enough that it is easier just to handle
            // on a case by case basis.
            mLockPatternView.setDisplayMode(DisplayMode.Correct);
            boolean announceAlways = false;

            switch (mUiStage) {
                case Introduction:
                    mLockPatternView.clearPattern();
                    break;
                case HelpScreen:
                    mLockPatternView.setPattern(DisplayMode.Animate, mAnimatePattern);
                    break;
                case ChoiceTooShort:
                    mLockPatternView.setDisplayMode(DisplayMode.Wrong);
                    postClearPatternRunnable();
                    announceAlways = true;
                    break;
                case FirstChoiceValid:
                    break;
                case NeedToConfirm:
                    mLockPatternView.clearPattern();
                    break;
                case ConfirmWrong:
                    mLockPatternView.setDisplayMode(DisplayMode.Wrong);
                    postClearPatternRunnable();
                    announceAlways = true;
                    break;
                case ChoiceConfirmed:
                    break;
            }

            // If the stage changed, announce the header for accessibility. This
            // is a no-op when accessibility is disabled.
            if (previousStage != stage || announceAlways) {
                mHeaderText.announceForAccessibility(mHeaderText.getText());
            }
        }

        protected void updateFooterLeftButton(Stage stage, TextView footerLeftButton) {
            if (stage.leftMode == LeftButtonMode.Gone) {
                footerLeftButton.setVisibility(View.GONE);
            } else {
                footerLeftButton.setVisibility(View.VISIBLE);
                footerLeftButton.setText(stage.leftMode.text);
                footerLeftButton.setEnabled(stage.leftMode.enabled);
            }
        }

        // clear the wrong pattern unless they have started a new one
        // already
        private void postClearPatternRunnable() {
            mLockPatternView.removeCallbacks(mClearPatternRunnable);
            mLockPatternView.postDelayed(mClearPatternRunnable, WRONG_PATTERN_CLEAR_TIMEOUT_MS);
        }

        private void startSaveAndFinish() {
            if (mSaveAndFinishWorker != null) {
                Log.w(TAG, "startSaveAndFinish with an existing SaveAndFinishWorker.");
                return;
            }

            setRightButtonEnabled(false);

            mSaveAndFinishWorker = new SaveAndFinishWorker();
            mSaveAndFinishWorker.setListener(this);

            getFragmentManager().beginTransaction().add(mSaveAndFinishWorker,
                    FRAGMENT_TAG_SAVE_AND_FINISH).commit();
            getFragmentManager().executePendingTransactions();

            final boolean required = getActivity().getIntent().getBooleanExtra(
                    EncryptionInterstitial.EXTRA_REQUIRE_PASSWORD, true);
            mSaveAndFinishWorker.start(mChooseLockSettingsHelper.utils(), required,
                    mHasChallenge, mChallenge, mChosenPattern, mCurrentPattern, mUserId);
        }

        @Override
        public void onChosenLockSaveFinished(boolean wasSecureBefore, Intent resultData) {
            getActivity().setResult(RESULT_FINISHED, resultData);

            if (!wasSecureBefore) {
                Intent intent = getRedactionInterstitialIntent(getActivity());
                if (intent != null) {
                    intent.putExtra(EXTRA_HIDE_DRAWER, mHideDrawer);
                    startActivity(intent);
                }
            }
            getActivity().finish();
        }
    }

    public static class SaveAndFinishWorker extends SaveChosenLockWorkerBase {

        private List<LockPatternView.Cell> mChosenPattern;
        private String mCurrentPattern;
        private boolean mLockVirgin;

        public void start(LockPatternUtils utils, boolean credentialRequired,
                boolean hasChallenge, long challenge,
                List<LockPatternView.Cell> chosenPattern, String currentPattern, int userId) {
            prepare(utils, credentialRequired, hasChallenge, challenge, userId);

            mCurrentPattern = currentPattern;
            mChosenPattern = chosenPattern;
            mUserId = userId;

            mLockVirgin = !mUtils.isPatternEverChosen(mUserId);

            start();
        }

        @Override
        protected Intent saveAndVerifyInBackground() {
            Intent result = null;
            final int userId = mUserId;
            mUtils.saveLockPattern(mChosenPattern, mCurrentPattern, userId);

            if (mHasChallenge) {
                byte[] token;
                try {
                    token = mUtils.verifyPattern(mChosenPattern, mChallenge, userId);
                } catch (RequestThrottledException e) {
                    token = null;
                }

                if (token == null) {
                    Log.e(TAG, "critical: no token returned for known good pattern");
                }

                result = new Intent();
                result.putExtra(ChooseLockSettingsHelper.EXTRA_KEY_CHALLENGE_TOKEN, token);
            }

            return result;
        }

        @Override
        protected void finish(Intent resultData) {
            if (mLockVirgin) {
                mUtils.setVisiblePatternEnabled(true, mUserId);
            }

            super.finish(resultData);
        }
    }
}
