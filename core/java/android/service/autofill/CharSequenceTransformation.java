/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.service.autofill;

import static android.view.autofill.Helper.sDebug;

import android.annotation.NonNull;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.view.autofill.AutofillId;
import android.widget.RemoteViews;
import android.widget.TextView;

import com.android.internal.util.Preconditions;

import java.util.regex.Pattern;

/**
 * Replaces a {@link TextView} child of a {@link CustomDescription} with the contents of one or
 * more regular expressions (regexs).
 *
 * <p>When it contains more than one field, the fields that match their regex are added to the
 * overall transformation result.
 *
 * <p>For example, a transformation to mask a credit card number contained in just one field would
 * be:
 *
 * <pre class="prettyprint">
 * new CharSequenceTransformation.Builder(ccNumberId, "^.*(\\d\\d\\d\\d)$", "...$1").build();
 * </pre>
 *
 * <p>But a transformation that generates a {@code Exp: MM / YYYY} credit expiration date from two
 * fields (month and year) would be:
 *
 * <pre class="prettyprint">
 * new CharSequenceTransformation.Builder(ccExpMonthId, "^(\\d\\d)$", "Exp: $1")
 *   .addField(ccExpYearId, "^(\\d\\d\\d\\d)$", " / $1");
 * </pre>
 */
public final class CharSequenceTransformation extends InternalTransformation implements
        Transformation, Parcelable {
    private static final String TAG = "CharSequenceTransformation";
    @NonNull private final ArrayMap<AutofillId, Pair<Pattern, String>> mFields;

    private CharSequenceTransformation(Builder builder) {
        mFields = builder.mFields;
    }

    /** @hide */
    @Override
    @TestApi
    public void apply(@NonNull ValueFinder finder, @NonNull RemoteViews parentTemplate,
            int childViewId) {
        final StringBuilder converted = new StringBuilder();
        final int size = mFields.size();
        if (sDebug) Log.d(TAG, size + " multiple fields on id " + childViewId);
        for (int i = 0; i < size; i++) {
            final AutofillId id = mFields.keyAt(i);
            final Pair<Pattern, String> regex = mFields.valueAt(i);
            final String value = finder.findByAutofillId(id);
            if (value == null) {
                Log.w(TAG, "No value for id " + id);
                return;
            }
            try {
                // replaceAll throws an exception if the subst is invalid
                final String convertedValue = regex.first.matcher(value).replaceAll(regex.second);
                converted.append(convertedValue);
            } catch (Exception e) {
                // Do not log full exception to avoid leaks
                Log.w(TAG, "Cannot apply " + regex.first.pattern() + "->" + regex.second + " to "
                        + "field with autofill id" + id + ": " + e.getClass());
            }
        }
        parentTemplate.setCharSequence(childViewId, "setText", converted);
    }

    /**
     * Builder for {@link CharSequenceTransformation} objects.
     */
    public static class Builder {
        @NonNull private final ArrayMap<AutofillId, Pair<Pattern, String>> mFields =
                new ArrayMap<>();
        private boolean mDestroyed;

        /**
         * Creates a new builder and adds the first transformed contents of a field to the overall
         * result of this transformation.
         *
         * @param id id of the screen field.
         * @param regex regular expression with groups (delimited by {@code (} and {@code (}) that
         * are used to substitute parts of the value. The pattern will be {@link Pattern#compile
         * compiled} without setting any flags.
         * @param subst the string that substitutes the matched regex, using {@code $} for
         * group substitution ({@code $1} for 1st group match, {@code $2} for 2nd, etc).
         */
        public Builder(@NonNull AutofillId id, @NonNull String regex, @NonNull String subst) {
            addField(id, regex, subst);
        }

        /**
         * Adds the transformed contents of a field to the overall result of this transformation.
         *
         * @param id id of the screen field.
         * @param regex regular expression with groups (delimited by {@code (} and {@code (}) that
         * are used to substitute parts of the value. The pattern will be {@link Pattern#compile
         * compiled} without setting any flags.
         * @param subst the string that substitutes the matched regex, using {@code $} for
         * group substitution ({@code $1} for 1st group match, {@code $2} for 2nd, etc).
         *
         * @return this builder.
         */
        public Builder addField(@NonNull AutofillId id, @NonNull String regex,
                @NonNull String subst) {
            throwIfDestroyed();
            Preconditions.checkNotNull(id);
            Preconditions.checkNotNull(regex);
            Preconditions.checkNotNull(subst);

            // Check if the regex is valid
            Pattern pattern = Pattern.compile(regex);

            mFields.put(id, new Pair<>(pattern, subst));
            return this;
        }

        /**
         * Creates a new {@link CharSequenceTransformation} instance.
         */
        public CharSequenceTransformation build() {
            throwIfDestroyed();
            mDestroyed = true;
            return new CharSequenceTransformation(this);
        }

        private void throwIfDestroyed() {
            Preconditions.checkState(!mDestroyed, "Already called build()");
        }
    }

    /////////////////////////////////////
    // Object "contract" methods. //
    /////////////////////////////////////
    @Override
    public String toString() {
        if (!sDebug) return super.toString();

        return "MultipleViewsCharSequenceTransformation: [fields=" + mFields + "]";
    }

    /////////////////////////////////////
    // Parcelable "contract" methods. //
    /////////////////////////////////////
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        final int size = mFields.size();
        final AutofillId[] ids = new AutofillId[size];
        final String[] regexs = new String[size];
        final String[] substs = new String[size];
        Pair<Pattern, String> pair;
        for (int i = 0; i < size; i++) {
            ids[i] = mFields.keyAt(i);
            pair = mFields.valueAt(i);
            regexs[i] = pair.first.pattern();
            substs[i] = pair.second;
        }
        parcel.writeParcelableArray(ids, flags);
        parcel.writeStringArray(regexs);
        parcel.writeStringArray(substs);
    }

    public static final Parcelable.Creator<CharSequenceTransformation> CREATOR =
            new Parcelable.Creator<CharSequenceTransformation>() {
        @Override
        public CharSequenceTransformation createFromParcel(Parcel parcel) {
            final AutofillId[] ids = parcel.readParcelableArray(null, AutofillId.class);
            final String[] regexs = parcel.createStringArray();
            final String[] substs = parcel.createStringArray();

            // Always go through the builder to ensure the data ingested by
            // the system obeys the contract of the builder to avoid attacks
            // using specially crafted parcels.
            final CharSequenceTransformation.Builder builder =
                    new CharSequenceTransformation.Builder(ids[0], regexs[0], substs[0]);

            final int size = ids.length;
            for (int i = 1; i < size; i++) {
                builder.addField(ids[i], regexs[i], substs[i]);
            }
            return builder.build();
        }

        @Override
        public CharSequenceTransformation[] newArray(int size) {
            return new CharSequenceTransformation[size];
        }
    };
}
