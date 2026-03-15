/* * Project: ProxiTrack - V9.5 (Final Commercial Release)
 * Features: Dual-State Telemetry, Client UI Timing, Soft-Unpair Command
 * Hardware: ESP32-C3 Super Mini
 */

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include <Preferences.h>
#include <driver/gpio.h>
#include <esp_sleep.h> 

// ---------- PIN DEFINITIONS ----------
#define BUZZER_PIN 10
#define LED_PIN 8 
#define BOOT_BUTTON 9
#define BATTERY_PIN 0 

// ---------- UUIDS ----------
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define AUTH_CHAR_UUID      "8d8218b6-97bc-4527-a8db-130940ddb633"
#define CMD_CHAR_UUID       "beb5483e-36e1-4688-b7f5-ea07361b26a8"

// ---------- GLOBAL VARIABLES ----------
Preferences prefs;
BLEServer* pServer = NULL;
BLEAdvertising *pAdvertising = NULL;
BLECharacteristic* pBatteryChar = NULL; 

String stored_pin = "0000";
volatile bool isUnlocked = false; 
volatile bool deviceConnected = false;
volatile unsigned long bootTime = 0;

// ---------- POWER SETTINGS (Client Requested) ----------
const unsigned long AWAKE_WINDOW_MS = 20000;   // 20 Seconds Awake
const uint64_t SLEEP_DURATION_US = 20000000;   // 20 Seconds Deep Sleep

// -------- BATTERY MATH --------
uint8_t getBatteryPercentage() {
    uint32_t mv = analogReadMilliVolts(BATTERY_PIN); 
    float pinVoltage = mv / 1000.0;
    float batVoltage = pinVoltage * 2.0; 
    float percentage = ((batVoltage - 3.3) / (4.2 - 3.3)) * 100.0;
    if (percentage > 100) percentage = 100;
    if (percentage < 0) percentage = 0;
    return (uint8_t)percentage;
}

// -------- GATT CALLBACKS --------
class ServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
        deviceConnected = true;
        isUnlocked = false;     
        digitalWrite(LED_PIN, LOW); // Client UI: LED ON when connected
        Serial.println("[BLE] App Connected. LED ON. Sleep Timer Canceled!");
        pServer->getAdvertising()->start();
    }

    void onDisconnect(BLEServer* pServer) {
        deviceConnected = false;
        isUnlocked = false;
        digitalWrite(BUZZER_PIN, LOW);
        digitalWrite(LED_PIN, HIGH); // Client UI: LED OFF when disconnected
        Serial.println("[BLE] App Disconnected. LED OFF. Restarting Sleep...");
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
                Serial.println("[BLE] Tag Claimed!");
            } else if (rxValue == stored_pin) {
                isUnlocked = true;
                Serial.println("[BLE] Auth Success!");
            } else {
                isUnlocked = false;
                Serial.println("[BLE] Auth Failed!");
            }
        }
    }
};

class CmdCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pChar) {
        if (!isUnlocked) return;
        String rxValue = pChar->getValue(); 
        
        if (rxValue == "1") {
            digitalWrite(BUZZER_PIN, HIGH);
            Serial.println("[BLE] Buzzer ON");
        } 
        else if (rxValue == "0") {
            digitalWrite(BUZZER_PIN, LOW);
            Serial.println("[BLE] Buzzer OFF");
        }
        else if (rxValue == "2") {
            // V9.5 FEATURE: The "Mutual Breakup" Software Reset
            Serial.println("[BLE] APP INITIATED UNPAIR! Wiping memory...");
            
            digitalWrite(BUZZER_PIN, HIGH); 
            vTaskDelay(500 / portTICK_PERIOD_MS); 
            digitalWrite(BUZZER_PIN, LOW);
            
            prefs.clear(); 
            vTaskDelay(500 / portTICK_PERIOD_MS);
            ESP.restart(); 
        }
    }
};

// -------- TASK 1: POWER & TELEMETRY SUPERVISOR --------
void powerManagementTask(void *pvParameters) {
    for (;;) {
        uint8_t currentBat = getBatteryPercentage();

        if (!deviceConnected && pAdvertising != NULL) {
            // STATE 1: Disconnected (Inject into Advertising Payload)
            uint8_t rawData[3] = {0xFF, 0xFF, currentBat};
            String mfgData = String((char*)rawData, 3);
            BLEAdvertisementData advData;
            advData.setFlags(ESP_BLE_ADV_FLAG_GEN_DISC | ESP_BLE_ADV_FLAG_BREDR_NOT_SPT);
            advData.setCompleteServices(BLEUUID(SERVICE_UUID));
            advData.setManufacturerData(mfgData);
            pAdvertising->setAdvertisementData(advData);
        } 
        else if (deviceConnected && pBatteryChar != NULL) {
            // STATE 2: Connected (Push Live GATT Notification to App)
            pBatteryChar->setValue(&currentBat, 1);
            pBatteryChar->notify();
            Serial.printf("[BLE] Live Battery Pushed to App: %d%%\n", currentBat);
        }

        if (!deviceConnected && (millis() - bootTime > AWAKE_WINDOW_MS)) {
            Serial.println("[Power Task] Entering Deep Sleep for 20 seconds...");
            pServer->getAdvertising()->stop();
            BLEDevice::deinit(true);
            esp_deep_sleep_start(); 
        }

        vTaskDelay(2000 / portTICK_PERIOD_MS); 
    }
}

// -------- TASK 2: SMART BUTTON LOGIC --------
void hardwareMonitorTask(void *pvParameters) {
    for (;;) {
        if (digitalRead(BOOT_BUTTON) == LOW) {
            vTaskDelay(50 / portTICK_PERIOD_MS);
            if (digitalRead(BOOT_BUTTON) == LOW) {
                unsigned long pressStart = millis();
                while (digitalRead(BOOT_BUTTON) == LOW) { vTaskDelay(10); }
                unsigned long duration = millis() - pressStart;

                if (duration > 5000) {
                    // LONG HOLD: Physical Factory Reset
                    Serial.println("[Hardware] FACTORY RESET!");
                    prefs.clear(); 
                    digitalWrite(BUZZER_PIN, HIGH); vTaskDelay(1000); digitalWrite(BUZZER_PIN, LOW);
                    ESP.restart(); 
                } else {
                    bool isDoubleClick = false;
                    unsigned long releaseTime = millis();
                    while (millis() - releaseTime < 400) {
                        if (digitalRead(BOOT_BUTTON) == LOW) {
                            isDoubleClick = true;
                            while (digitalRead(BOOT_BUTTON) == LOW) { vTaskDelay(10); }
                            break;
                        }
                        vTaskDelay(10);
                    }

                    if (isDoubleClick) {
                        // DOUBLE CLICK: Infinite Power OFF (Light Sleep fix for GPIO 9)
                        Serial.println("[Hardware] Power OFF (Infinite Sleep)...");
                        for(int i=0; i<3; i++) { digitalWrite(LED_PIN, LOW); vTaskDelay(100); digitalWrite(LED_PIN, HIGH); vTaskDelay(100); }
                        pServer->getAdvertising()->stop();
                        BLEDevice::deinit(true);
                        esp_sleep_disable_wakeup_source(ESP_SLEEP_WAKEUP_TIMER); 
                        gpio_wakeup_enable((gpio_num_t)BOOT_BUTTON, GPIO_INTR_LOW_LEVEL);
                        esp_sleep_enable_gpio_wakeup();
                        Serial.println("[Hardware] System asleep. Press BOOT button to turn back on.");
                        esp_light_sleep_start(); 
                        Serial.println("[Hardware] Waking up! Rebooting system...");
                        ESP.restart();
                    } else {
                        // SINGLE CLICK: Test Beep
                        digitalWrite(BUZZER_PIN, HIGH); vTaskDelay(150); digitalWrite(BUZZER_PIN, LOW);
                    }
                }
            }
        }
        vTaskDelay(50 / portTICK_PERIOD_MS); 
    }
}

// -------- SETUP --------
void setup() {
    Serial.begin(115200);
    delay(2000);

    bootTime = millis(); 
    
    pinMode(BUZZER_PIN, OUTPUT);
    digitalWrite(BUZZER_PIN, LOW);

    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, HIGH);

    pinMode(BOOT_BUTTON, INPUT_PULLUP);
    
    digitalWrite(LED_PIN, LOW); delay(50); digitalWrite(LED_PIN, HIGH);

    prefs.begin("proxitrack", false);
    stored_pin = prefs.getString("pin", "0000"); 

    BLEDevice::init("ProxiNode");
    BLEDevice::setPower(ESP_PWR_LVL_P9);
    esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_ADV, ESP_PWR_LVL_P9);

    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    // 1. Core Custom Service (Auth & Cmd)
    BLEService *pService = pServer->createService(SERVICE_UUID);
    BLECharacteristic *pAuthChar = pService->createCharacteristic(AUTH_CHAR_UUID, BLECharacteristic::PROPERTY_WRITE);
    pAuthChar->setCallbacks(new AuthCallbacks());
    BLECharacteristic *pCmdChar = pService->createCharacteristic(CMD_CHAR_UUID, BLECharacteristic::PROPERTY_WRITE);
    pCmdChar->setCallbacks(new CmdCallbacks());
    pService->start();

    // 2. Standard Battery Service (0x180F) for live GATT updates
    BLEService *pBatService = pServer->createService(BLEUUID((uint16_t)0x180F));
    pBatteryChar = pBatService->createCharacteristic(
        BLEUUID((uint16_t)0x2A19),
        BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY
    );
    pBatteryChar->addDescriptor(new BLE2902()); 
    pBatService->start();

    pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06);

    uint8_t initialBat = getBatteryPercentage();
    uint8_t rawData[3] = {0xFF, 0xFF, initialBat};
    String mfgData = String((char*)rawData, 3);

    BLEAdvertisementData advData;
    advData.setFlags(ESP_BLE_ADV_FLAG_GEN_DISC | ESP_BLE_ADV_FLAG_BREDR_NOT_SPT);
    advData.setCompleteServices(BLEUUID(SERVICE_UUID));
    advData.setManufacturerData(mfgData);

    pAdvertising->setAdvertisementData(advData);
    pAdvertising->start();

    esp_sleep_enable_timer_wakeup(SLEEP_DURATION_US);

    xTaskCreate(powerManagementTask, "PowerTask", 2048, NULL, 1, NULL);
    xTaskCreate(hardwareMonitorTask, "HardwareTask", 2048, NULL, 1, NULL);

    vTaskDelete(NULL); 
}

void loop() {}