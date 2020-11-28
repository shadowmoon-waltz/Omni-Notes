/*
 * Copyright (C) 2020 shadowmoon_waltz
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

// https://stackoverflow.com/questions/29115216/how-to-make-a-preference-long-clickable-in-preferencefragment

package it.feio.android.omninotes.widget;


import android.content.Context;
import android.util.AttributeSet;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SwitchPreference;


public class LongClickableSwitchPreference extends SwitchPreference {

  public interface OnPreferenceLongClickListener {
    boolean onPreferenceLongClickListener(Preference pref);
  }
  
  
  private OnPreferenceLongClickListener onPreferenceLongClickListener;
  
  
  public LongClickableSwitchPreference(Context context, AttributeSet attrs) {
    super(context, attrs);
  }


  public OnPreferenceLongClickListener getOnPreferenceLongClickListener() {
    return onPreferenceLongClickListener;
  }
  
  
  public void setOnPreferenceLongClickListener(OnPreferenceLongClickListener listener) {
    onPreferenceLongClickListener = listener;
  }
  
  @Override
  public void onBindViewHolder(PreferenceViewHolder holder) {
    super.onBindViewHolder(holder);
    holder.itemView.setOnLongClickListener(v -> {
      if (isEnabled() && isSelectable() && onPreferenceLongClickListener != null) {
        return onPreferenceLongClickListener.onPreferenceLongClickListener(this);
      }
      return false;
    });
  }

}
