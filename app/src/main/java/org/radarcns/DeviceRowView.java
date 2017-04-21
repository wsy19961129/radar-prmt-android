/*
 * Copyright 2017 The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarcns;

import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.RemoteException;
import android.support.v7.app.AlertDialog;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import org.radarcns.android.MainActivity;
import org.radarcns.android.device.BaseDeviceState;
import org.radarcns.android.device.DeviceServiceConnection;
import org.radarcns.android.device.DeviceServiceProvider;
import org.radarcns.android.device.DeviceStatusListener;
import org.radarcns.android.util.Boast;
import org.radarcns.data.TimedInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.DecimalFormat;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Displays a single device row.
 */
public class DeviceRowView {
    private static final Logger logger = LoggerFactory.getLogger(DeviceRowView.class);
    private static final int MAX_UI_DEVICE_NAME_LENGTH = 25;

    private final static Map<DeviceStatusListener.Status, Integer> deviceStatusIconMap;
    private final static int deviceStatusIconDefault = org.radarcns.R.drawable.status_searching;

    static {
        deviceStatusIconMap = new EnumMap<>(DeviceStatusListener.Status.class);
        deviceStatusIconMap.put(DeviceStatusListener.Status.CONNECTED, org.radarcns.R.drawable.status_connected);
        deviceStatusIconMap.put(DeviceStatusListener.Status.DISCONNECTED, org.radarcns.R.drawable.status_disconnected);
        deviceStatusIconMap.put(DeviceStatusListener.Status.READY, org.radarcns.R.drawable.status_searching);
        deviceStatusIconMap.put(DeviceStatusListener.Status.CONNECTING, org.radarcns.R.drawable.status_searching);
    }

    private final MainActivity mainActivity;
    // Data formats
    private final DecimalFormat singleDecimal = new DecimalFormat("0.0");
    private final DecimalFormat doubleDecimal = new DecimalFormat("0.00");
    private final DecimalFormat noDecimals = new DecimalFormat("0");


    private final DeviceServiceConnection connection;
    private final boolean condensedDisplay;
    private final View mStatusIcon;
    private final TextView mTemperatureLabel;
    private final TextView mHeartRateLabel;
    private final TextView mAccelerationLabel;
    private final TextView mRecordsSentLabel;
    private final ImageView mBatteryLabel;
    private final TextView mBatteryValue;
    private final TextView mDeviceNameLabel;
    private final Button mDeviceInputButton;
    private final SharedPreferences devicePreferences;
    private String filter;
    private BaseDeviceState state;
    private String deviceName;
    private TimedInt previousRecordsSent;
    private float previousTemperature = Float.NaN;
    private float previousBatteryLevel = Float.NaN;
    private float previousHeartRate = Float.NaN;
    private float previousAcceleration = Float.NaN;
    private int previousRecordsSentTimer = -1;
    private String previousName;
    private DeviceStatusListener.Status previousStatus = null;

    DeviceRowView(MainActivity mainActivity, DeviceServiceProvider provider, ViewGroup root, boolean condensedDisplay) {
        this.mainActivity = mainActivity;
        this.connection = provider.getConnection();
        this.condensedDisplay = condensedDisplay;
        devicePreferences = this.mainActivity.getSharedPreferences("device." + connection.getServiceClassName(), Context.MODE_PRIVATE);
        logger.info("Creating device row for provider {} and connection {}", provider, connection);
        LayoutInflater inflater = (LayoutInflater) this.mainActivity.getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        inflater.inflate(R.layout.activity_overview_device_row, root);
        TableRow row = (TableRow) root.getChildAt(root.getChildCount() - 1);
        TextView deviceTypeLabel = (TextView) row.findViewById(R.id.deviceType);
        deviceTypeLabel.setText(provider.getDisplayName());

        mStatusIcon = row.findViewById(R.id.status_icon);
        mTemperatureLabel = (TextView) row.findViewById(R.id.temperature_label);
        mHeartRateLabel = (TextView) row.findViewById(R.id.heartRate_label);
        mAccelerationLabel = (TextView) row.findViewById(R.id.acceleration_label);
        mRecordsSentLabel = (TextView) row.findViewById(R.id.recordsSent_label);
        mDeviceNameLabel = (TextView) row.findViewById(R.id.deviceName_label);
        mBatteryLabel = (ImageView) row.findViewById(R.id.battery_label);
        mBatteryValue = (TextView) row.findViewById(R.id.battery_value);
        mDeviceInputButton = (Button) row.findViewById(R.id.inputDeviceNameButton);

        if (provider.isFilterable()) {
            mDeviceInputButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialogDeviceName();
                }
            });
            mDeviceInputButton.setVisibility(View.VISIBLE);
        }

        filter = "";
        setFilter(devicePreferences.getString("filter", ""));
        row.findViewById(R.id.refreshButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                reconnectDevice();
            }
        });
    }

    public void dialogDeviceName() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this.mainActivity);
        builder.setTitle(this.mainActivity.getString(R.string.filter_title));

        final RelativeLayout layout = new RelativeLayout(this.mainActivity);
        TextView label = new TextView(this.mainActivity);
        label.setText(R.string.filter_help_label);
        layout.addView(label);
        // Set up the input
        final EditText input = new EditText(this.mainActivity);
        // Specify the type of input expected
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        layout.addView(input);
        builder.setView(layout);

        // Set up the buttons
        input.setText(filter);
        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                setFilter(input.getText().toString().trim());
            }
        });
        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });

        builder.show();
    }

    private void setFilter(String newValue) {
        if (filter.equals(newValue)) {
            logger.info("device filter did not change - ignoring");
            return;
        }
        // Set new value and process
        filter = newValue;
        devicePreferences.edit().putString("filter", filter).apply();

        String splitRegex = this.mainActivity.getString(R.string.filter_split_regex);
        Set<String> allowed = new HashSet<>();
        for (String s : filter.split(splitRegex)) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                allowed.add(trimmed);
            }
        }

        logger.info("setting device filter {}", allowed);
        if (allowed.isEmpty()) {
            mDeviceInputButton.setText(R.string.button_input);
        } else {
            String allowedString = allowed.toString();
            // strip array indicators
            allowedString = allowedString.substring(1, allowedString.length() - 1);
            mDeviceInputButton.setText(allowedString);
        }

        this.mainActivity.setAllowedDeviceIds(connection, allowed);
    }

    public void reconnectDevice() {
        try {
            // will restart scanning after disconnect
            this.mainActivity.disconnect(connection);
        } catch (IndexOutOfBoundsException iobe) {
            Boast.makeText(this.mainActivity, "Could not restart scanning, there is no valid row index associated with this button.", Toast.LENGTH_LONG).show();
            logger.warn(iobe.getMessage());
        }
    }

    public void update() throws RemoteException {
        if (connection.hasService()) {
            state = connection.getDeviceData();
            switch (state.getStatus()) {
                case CONNECTED:
                case CONNECTING:
                    deviceName = connection.getDeviceName();
                    break;
                default:
                    deviceName = null;
                    break;
            }
        } else {
            state = null;
            deviceName = null;
        }
    }

    public void display() {
        updateAcceleration();
        updateBattery();
        updateDeviceName();
        updateDeviceStatus();
        updateDeviceTotalRecordsSent();
        updateHeartRate();
        updateTemperature();
    }

    public void updateDeviceStatus() {
        // Connection status. Change icon used.
        DeviceStatusListener.Status status;
        if (state == null) {
            status = DeviceStatusListener.Status.DISCONNECTED;
        } else {
            status = state.getStatus();
        }
        if (!Objects.equals(status, previousStatus)) {
            logger.info("Device status is {}", status);
            previousStatus = status;
            Integer statusIcon = deviceStatusIconMap.get(status);
            int resource = statusIcon != null ? statusIcon : deviceStatusIconDefault;
            mStatusIcon.setBackgroundResource(resource);
        }
    }

    public void updateTemperature() {
        if (state != null && !state.hasTemperature()) {
            return;
        }
        // \u2103 == ℃
        float temperature = state == null ? Float.NaN : state.getTemperature();
        if (Objects.equals(previousTemperature, temperature)) {
            return;
        }
        previousTemperature = temperature;
        setText(mTemperatureLabel, temperature, "\u2103", singleDecimal);
    }

    public void updateHeartRate() {
        if (state != null && !state.hasHeartRate()) {
            return;
        }
        float heartRate = state == null ? Float.NaN : state.getHeartRate();
        if (Objects.equals(previousHeartRate, heartRate)) {
            return;
        }
        previousHeartRate = heartRate;
        setText(mHeartRateLabel, heartRate, "bpm", noDecimals);
    }

    public void updateAcceleration() {
        if (state != null && !state.hasAcceleration()) {
            return;
        }
        float acceleration = state == null ? Float.NaN : state.getAccelerationMagnitude();
        if (Objects.equals(previousAcceleration, acceleration)) {
            return;
        }
        previousAcceleration = acceleration;
        setText(mAccelerationLabel, acceleration, "g", doubleDecimal);
    }

    public void updateBattery() {
        // Battery levels observed for E4 are 0.01, 0.1, 0.45 or 1
        float batteryLevel = state == null ? Float.NaN : state.getBatteryLevel();
        if (Objects.equals(previousBatteryLevel, batteryLevel)) {
            return;
        }
        previousBatteryLevel = batteryLevel;
        if (Float.isNaN(batteryLevel)) {
            mBatteryLabel.setImageResource(R.drawable.ic_battery_unknown);
        } else if (batteryLevel < 0.1) {
            mBatteryLabel.setImageResource(R.drawable.ic_battery_empty);
        } else if (batteryLevel < 0.3) {
            mBatteryLabel.setImageResource(R.drawable.ic_battery_low);
        } else if (batteryLevel < 0.6) {
            mBatteryLabel.setImageResource(R.drawable.ic_battery_50);
        } else {
            mBatteryLabel.setImageResource(R.drawable.ic_battery_full);
        }

        // Display battery level value. If 100%, make it 99% for better layout
        float batteryLevelFormatted = batteryLevel == 1 ? batteryLevel*100 - 1 : batteryLevel*100;
        setText(mBatteryValue, batteryLevelFormatted, "", noDecimals);
    }

    public void updateDeviceName() {
        if (Objects.equals(deviceName, previousName)) {
            return;
        }
        previousName = deviceName;
        // Restrict length of name that is shown.
        if (deviceName != null && deviceName.length() > MAX_UI_DEVICE_NAME_LENGTH - 3) {
            deviceName = deviceName.substring(0, MAX_UI_DEVICE_NAME_LENGTH) + "...";
        }

        // \u2014 == —
        mDeviceNameLabel.setText(deviceName == null ? "\u2014" : deviceName);
    }

    public void updateDeviceTotalRecordsSent() {
        TimedInt recordsSent = this.mainActivity.getTopicsSent(connection);
        if (recordsSent.getTime() == -1L) {
            if (previousRecordsSent != null && previousRecordsSent.getTime() == -1L) {
                return;
            }
            mRecordsSentLabel.setText(R.string.emptyText);
        } else {
            int timeSinceLastUpdate = (int) ((System.currentTimeMillis() - recordsSent.getTime()) / 1000L);
            if (previousRecordsSent != null && previousRecordsSent.equals(recordsSent) && previousRecordsSentTimer == timeSinceLastUpdate) {
                return;
            }
            // Small test for Firebase Remote config.
            String message;
            if (condensedDisplay) {
                message = String.format(Locale.US, "%1$4dk (%2$d)",
                        recordsSent.getValue() / 1000, timeSinceLastUpdate);
            } else {
                message = String.format(Locale.US, "%1$4d (updated %2$d sec. ago)",
                        recordsSent.getValue(), timeSinceLastUpdate);
            }
            mRecordsSentLabel.setText(message);
            previousRecordsSentTimer = timeSinceLastUpdate;
        }
        previousRecordsSent = recordsSent;
    }

    private void setText(TextView label, float value, String suffix, DecimalFormat formatter) {
        if (Float.isNaN(value)) {
            // Only overwrite default value if enabled.
            if (label.isEnabled()) {
                // em dash
                label.setText("\u2014");
            }
        } else {
            label.setText(formatter.format(value) + " " + suffix);
        }
    }
}
