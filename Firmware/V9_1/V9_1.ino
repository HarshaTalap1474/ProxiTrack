/* * Project: ProxiTrack - Official Hardware Handover (DIPEX 2026)
 * Firmware: V9.1 (FreeRTOS + Battery Service + Smart Button)
 * Hardware: ESP32-C3 Super Mini
 */

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include <Preferences.h>
#include <esp_sleep.h> 

// --- PIN DEFINITIONS ---
#define BUZZER_PIN 10
#define LED_PIN 8 
#define BOOT_BUTTON 9 
#define BATTERY_PIN 0 // The middle tap of your 100k/100k resistor bridge

// --- CONTRACT UUIDS ---
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define AUTH_CHAR_UUID      "8d8218b6-97bc-4527-a8db-130940ddb633"
#define CMD_CHAR_UUID       "beb5483e-36e1-4688-b7f5-ea07361b26a8"
#define BATTERY_SERVICE_UUID (uint16_t)0x180F // Official BLE Battery Service

// --- GLOBAL STATE ---
Preferences prefs;
BLEServer* pServer = NULL;
BLECharacteristic* pBatteryChar = NULL;
String stored_pin = "0000";

volatile bool isUnlocked = false; 
volatile bool deviceConnected = false;
volatile unsigned long bootTime = 0;

const unsigned long AWAKE_WINDOW_MS = 10000; 
const uint64_t SLEEP_DURATION_US = 5000000;  

// --- BATTERY MATH ---
uint8_t getBatteryPercentage() {
    // Read the analog pin in millivolts for high accuracy on the C3
    uint32_t mv = analogReadMilliVolts(BATTERY_PIN); 
    
    // The voltage divider cut the voltage in half, so we multiply by 2
    float pinVoltage = mv / 1000.0;
    float batVoltage = pinVoltage * 2.0; 
    
    // Map Li-ion voltage (3.3V Dead -> 4.2V Full) to 0-100%
    float percentage = ((batVoltage - 3.3) / (4.2 - 3.3)) * 100.0;
    
    if (percentage > 100) percentage = 100;
    if (percentage < 0) percentage = 0;
    
    return (uint8_t)percentage;
}

// --- GATT CALLBACKS ---
class ServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
        deviceConnected = true; 
        isUnlocked = false;     
        Serial.println("[BLE] App Connected. Sleep Timer Canceled!");
        pServer->getAdvertising()->start();
    }
    
    void onDisconnect(BLEServer* pServer) {
        deviceConnected = false; 
        isUnlocked = false;
        digitalWrite(BUZZER_PIN, LOW); 
        digitalWrite(LED_PIN, HIGH); 
        Serial.println("[BLE] App Disconnected. Restarting Sleep Timer...");
        bootTime = millis(); 
        pServer->getAdvertising()->start();
    }
};

class AuthCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pChar) {
        String rxValue = pChar->getValue(); 
        if (rxValue.length() > 0) {
            if (stored_pin == "0000") {
                stored_pin = rxValue;
                prefs.putString("pin", stored_pin);
                isUnlocked = true;
                Serial.println("[BLE] SECURITY: Tag Claimed! PIN: " + stored_pin);
            } else if (rxValue == stored_pin) {
                isUnlocked = true;
                Serial.println("[BLE] SECURITY: Auth Success! Tag Unlocked.");
            } else {
                isUnlocked = false;
                Serial.println("[BLE] SECURITY: Auth Failed!");
            }
        }
    }
};

class CmdCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pChar) {
        if (!isUnlocked) return;
        String rxValue = pChar->getValue(); 
        if (rxValue.length() > 0) {
            if (rxValue[0] == '1') {
                digitalWrite(BUZZER_PIN, HIGH); digitalWrite(LED_PIN, LOW); 
                Serial.println("[BLE] Buzzer ON");
            } else if (rxValue[0] == '0') {
                digitalWrite(BUZZER_PIN, LOW); digitalWrite(LED_PIN, HIGH); 
                Serial.println("[BLE] Buzzer OFF");
            }
        }
    }
};

// --- TASK 1: POWER & BATTERY SUPERVISOR ---
void powerManagementTask(void *pvParameters) {
    for (;;) {
        // Update battery level every few seconds if connected
        if (deviceConnected && pBatteryChar != NULL) {
            uint8_t batLvl = getBatteryPercentage();
            pBatteryChar->setValue(&batLvl, 1);
            pBatteryChar->notify();
        }

        if (!deviceConnected && (millis() - bootTime > AWAKE_WINDOW_MS)) {
            Serial.println("[Power Task] Entering Deep Sleep for 5 seconds...");
            pServer->getAdvertising()->stop();
            BLEDevice::deinit(true);
            esp_deep_sleep_start(); 
        }
        vTaskDelay(2000 / portTICK_PERIOD_MS); 
    }
}

// --- TASK 2: SMART BUTTON LOGIC ---
void hardwareMonitorTask(void *pvParameters) {
    for (;;) {
        if (digitalRead(BOOT_BUTTON) == LOW) {
            vTaskDelay(50 / portTICK_PERIOD_MS); // Debounce
            if (digitalRead(BOOT_BUTTON) == LOW) {
                unsigned long pressStart = millis();
                while (digitalRead(BOOT_BUTTON) == LOW) { vTaskDelay(10); } // Wait for release
                unsigned long duration = millis() - pressStart;

                if (duration > 5000) {
                    // LONG HOLD: Factory Reset
                    Serial.println("[Hardware] FACTORY RESET TRIGGERED!");
                    prefs.clear(); 
                    digitalWrite(BUZZER_PIN, HIGH); vTaskDelay(1000); digitalWrite(BUZZER_PIN, LOW);
                    ESP.restart(); 
                } 
                else {
                    // Wait to see if it's a double click
                    bool isDoubleClick = false;
                    unsigned long releaseTime = millis();
                    while (millis() - releaseTime < 400) {
                        if (digitalRead(BOOT_BUTTON) == LOW) {
                            isDoubleClick = true;
                            while (digitalRead(BOOT_BUTTON) == LOW) { vTaskDelay(10); } // Wait for release
                            break;
                        }
                        vTaskDelay(10);
                    }

                    if (isDoubleClick) {
                        // DOUBLE CLICK: Infinite Sleep (Power OFF)
                        Serial.println("[Hardware] Powering OFF (Infinite Sleep)...");
                        for(int i=0; i<3; i++) { digitalWrite(LED_PIN, LOW); vTaskDelay(100); digitalWrite(LED_PIN, HIGH); vTaskDelay(100); }
                        pServer->getAdvertising()->stop();
                        BLEDevice::deinit(true);
                        esp_sleep_disable_wakeup_source(ESP_SLEEP_WAKEUP_TIMER); // Disable 5s timer
                        esp_deep_sleep_enable_gpio_wakeup(1 << BOOT_BUTTON, ESP_GPIO_WAKEUP_GPIO_LOW); // Wake on button press
                        esp_deep_sleep_start();
                    } else {
                        // SINGLE CLICK: Local Test Beep
                        Serial.println("[Hardware] Local Test Beep");
                        digitalWrite(BUZZER_PIN, HIGH); vTaskDelay(150); digitalWrite(BUZZER_PIN, LOW);
                    }
                }
            }
        }
        vTaskDelay(50 / portTICK_PERIOD_MS); 
    }
}

void setup() {
    Serial.begin(115200);
    delay(2000); 
    bootTime = millis(); 
    
    pinMode(BUZZER_PIN, OUTPUT); digitalWrite(BUZZER_PIN, LOW);
    pinMode(LED_PIN, OUTPUT); digitalWrite(LED_PIN, HIGH); 
    pinMode(BOOT_BUTTON, INPUT_PULLUP);
    
    // Heartbeat
    digitalWrite(LED_PIN, LOW); delay(50); digitalWrite(LED_PIN, HIGH);

    prefs.begin("proxitrack", false);
    stored_pin = prefs.getString("pin", "0000"); 

    BLEDevice::init("ProxiNode");
    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    // Main Service
    BLEService *pService = pServer->createService(SERVICE_UUID);
    BLECharacteristic *pAuthChar = pService->createCharacteristic(AUTH_CHAR_UUID, BLECharacteristic::PROPERTY_WRITE);
    pAuthChar->setCallbacks(new AuthCallbacks());
    BLECharacteristic *pCmdChar = pService->createCharacteristic(CMD_CHAR_UUID, BLECharacteristic::PROPERTY_WRITE);
    pCmdChar->setCallbacks(new CmdCallbacks());
    pService->start();

    // Battery Service (Standard 180F)
    BLEService *pBatteryService = pServer->createService(BLEUUID(BATTERY_SERVICE_UUID));
    pBatteryChar = pBatteryService->createCharacteristic(BLEUUID((uint16_t)0x2A19), BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
    uint8_t initialBat = getBatteryPercentage();
    pBatteryChar->setValue(&initialBat, 1);
    pBatteryService->start();

    // Advertising
    BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->addServiceUUID(BLEUUID(BATTERY_SERVICE_UUID)); // Advertise battery!
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06); 
    pAdvertising->start();

    esp_sleep_enable_timer_wakeup(SLEEP_DURATION_US);

    xTaskCreate(powerManagementTask, "PowerTask", 2048, NULL, 1, NULL);
    xTaskCreate(hardwareMonitorTask, "HardwareTask", 2048, NULL, 1, NULL);

    Serial.println("\n[Main] V9.1 System Online. Battery: " + String(initialBat) + "%");
    vTaskDelete(NULL); 
}

void loop() {}