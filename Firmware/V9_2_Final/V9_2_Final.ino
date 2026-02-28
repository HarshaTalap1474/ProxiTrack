/* 
 * Project: ProxiTrack - Official Hardware Handover (DIPEX 2026)
 * Firmware: V9.2 (Android Sync Patch: BLE MAC Reveal + Mfg Payload)
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

#define BUZZER_PIN 10
#define LED_PIN 8 
#define BOOT_BUTTON 9
#define BATTERY_PIN 0 

#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define AUTH_CHAR_UUID      "8d8218b6-97bc-4527-a8db-130940ddb633"
#define CMD_CHAR_UUID       "beb5483e-36e1-4688-b7f5-ea07361b26a8"
#define BATTERY_SERVICE_UUID (uint16_t)0x180F 

Preferences prefs;
BLEServer* pServer = NULL;
BLECharacteristic* pBatteryChar = NULL;
BLEAdvertising *pAdvertising = NULL;
String stored_pin = "0000";

volatile bool isUnlocked = false; 
volatile bool deviceConnected = false;
volatile unsigned long bootTime = 0;

const unsigned long AWAKE_WINDOW_MS = 10000; 
const uint64_t SLEEP_DURATION_US = 5000000;  

// -------- Battery --------
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
        Serial.println("[BLE] App Connected. Sleep Timer Canceled!");
        pServer->getAdvertising()->start();
    }

    void onDisconnect(BLEServer* pServer) {
        deviceConnected = false;
        isUnlocked = false;
        digitalWrite(BUZZER_PIN, LOW);
        digitalWrite(LED_PIN, HIGH); 
        Serial.println("[BLE] App Disconnected. Restarting Sleep...");
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
        if (rxValue.length() > 0) {
            if (rxValue[0] == '1') {
                digitalWrite(BUZZER_PIN, HIGH);
                digitalWrite(LED_PIN, LOW);
            } else if (rxValue[0] == '0') {
                digitalWrite(BUZZER_PIN, LOW);
                digitalWrite(LED_PIN, HIGH);
            }
        }
    }
};

// -------- TASK 1: POWER & ADVERTISING SUPERVISOR --------
void powerManagementTask(void *pvParameters) {
    for (;;) {

        if (!deviceConnected && pAdvertising != NULL) {

            uint8_t currentBat = getBatteryPercentage();

            // FIXED: Proper Arduino String binary payload
            uint8_t rawData[3];
            rawData[0] = 0xFF;
            rawData[1] = 0xFF;
            rawData[2] = currentBat;

            String mfgData = String((char*)rawData, 3);

            BLEAdvertisementData advData;
            advData.setFlags(ESP_BLE_ADV_FLAG_GEN_DISC | ESP_BLE_ADV_FLAG_BREDR_NOT_SPT);
            advData.setCompleteServices(BLEUUID(SERVICE_UUID));
            advData.setManufacturerData(mfgData);

            pAdvertising->setAdvertisementData(advData);
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

// -------- TASK 2: SMART BUTTON LOGIC --------
void hardwareMonitorTask(void *pvParameters) {
    for (;;) {

        if (digitalRead(BOOT_BUTTON) == LOW) {

            vTaskDelay(50 / portTICK_PERIOD_MS);

            if (digitalRead(BOOT_BUTTON) == LOW) {

                unsigned long pressStart = millis();

                while (digitalRead(BOOT_BUTTON) == LOW) {
                    vTaskDelay(10);
                }

                unsigned long duration = millis() - pressStart;

                if (duration > 5000) {

                    Serial.println("[Hardware] FACTORY RESET!");
                    prefs.clear(); 

                    digitalWrite(BUZZER_PIN, HIGH);
                    vTaskDelay(1000);
                    digitalWrite(BUZZER_PIN, LOW);

                    ESP.restart(); 

                } else {

                    bool isDoubleClick = false;
                    unsigned long releaseTime = millis();

                    while (millis() - releaseTime < 400) {
                        if (digitalRead(BOOT_BUTTON) == LOW) {
                            isDoubleClick = true;
                            while (digitalRead(BOOT_BUTTON) == LOW) {
                                vTaskDelay(10);
                            }
                            break;
                        }
                        vTaskDelay(10);
                    }

                    if (isDoubleClick) {

                        Serial.println("[Hardware] Power OFF (Infinite Sleep)...");

                        for(int i=0; i<3; i++) {
                            digitalWrite(LED_PIN, LOW);
                            vTaskDelay(100);
                            digitalWrite(LED_PIN, HIGH);
                            vTaskDelay(100);
                        }

                        pServer->getAdvertising()->stop();
                        BLEDevice::deinit(true);

                        esp_sleep_disable_wakeup_source(ESP_SLEEP_WAKEUP_TIMER); 
                        
                        // FIX: GPIO 9 is not an RTC pin. We must use Light Sleep!
                        gpio_wakeup_enable((gpio_num_t)BOOT_BUTTON, GPIO_INTR_LOW_LEVEL);
                        esp_sleep_enable_gpio_wakeup();
                        
                        Serial.println("[Hardware] System asleep. Press BOOT button to turn back on.");
                        esp_light_sleep_start(); // Enters Light Sleep instead of Deep Sleep

                        // Unlike Deep Sleep, Light Sleep continues exactly where it left off.
                        // So when you press the button to wake it up, we force a full restart!
                        Serial.println("[Hardware] Waking up! Rebooting system...");
                        ESP.restart();

                    } else {
                        digitalWrite(BUZZER_PIN, HIGH);
                        vTaskDelay(150);
                        digitalWrite(BUZZER_PIN, LOW);
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
    
    digitalWrite(LED_PIN, LOW);
    delay(50);
    digitalWrite(LED_PIN, HIGH);

    prefs.begin("proxitrack", false);
    stored_pin = prefs.getString("pin", "0000"); 

    BLEDevice::init("ProxiNode");
    
    Serial.println("\n=======================================================");
    Serial.print("CRITICAL: YOUR TRUE BLE MAC ADDRESS IS: ");
    Serial.println(BLEDevice::getAddress().toString().c_str());
    Serial.println("Encode THIS address into the Android App/QR Code!");
    Serial.println("=======================================================\n");

    esp_ble_tx_power_set(ESP_BLE_PWR_TYPE_ADV, ESP_PWR_LVL_P9);

    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());

    BLEService *pService = pServer->createService(SERVICE_UUID);

    BLECharacteristic *pAuthChar = pService->createCharacteristic(
        AUTH_CHAR_UUID,
        BLECharacteristic::PROPERTY_WRITE
    );
    pAuthChar->setCallbacks(new AuthCallbacks());

    BLECharacteristic *pCmdChar = pService->createCharacteristic(
        CMD_CHAR_UUID,
        BLECharacteristic::PROPERTY_WRITE
    );
    pCmdChar->setCallbacks(new CmdCallbacks());

    pService->start();

    pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06);

    // FIXED: Initial manufacturer payload
    uint8_t initialBat = getBatteryPercentage();

    uint8_t rawData[3];
    rawData[0] = 0xFF;
    rawData[1] = 0xFF;
    rawData[2] = initialBat;

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