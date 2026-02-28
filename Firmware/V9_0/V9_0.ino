/* * Project: ProxiTrack - Official Hardware Handover (DIPEX 2026)
 * Firmware: V9.0 (The RTOS Edition: Multitasking + Security + Sleep)
 * Hardware: ESP32-C3 Super Mini (Single Core RISC-V)
 */

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include <Preferences.h>
#include <esp_sleep.h> 

// --- PIN DEFINITIONS ---
#define BUZZER_PIN 10
#define LED_PIN 8 
#define BOOT_BUTTON 9 

// --- CONTRACT UUIDS ---
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define AUTH_CHAR_UUID      "8d8218b6-97bc-4527-a8db-130940ddb633"
#define CMD_CHAR_UUID       "beb5483e-36e1-4688-b7f5-ea07361b26a8"

// --- GLOBAL STATE ---
Preferences prefs;
BLEServer* pServer = NULL;
String stored_pin = "0000";

// Volatile keyword is CRITICAL in RTOS so tasks share the updated memory
volatile bool isUnlocked = false; 
volatile bool deviceConnected = false;
volatile unsigned long bootTime = 0;

const unsigned long AWAKE_WINDOW_MS = 10000; 
const uint64_t SLEEP_DURATION_US = 5000000;  

// --- GATT CALLBACKS (Task 1: BLE Native Context) ---

class ServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
        deviceConnected = true; 
        isUnlocked = false;     
        Serial.println("[BLE Task] App Connected. Sleep Timer Canceled!");
        pServer->getAdvertising()->start();
    }
    
    void onDisconnect(BLEServer* pServer) {
        deviceConnected = false; 
        isUnlocked = false;
        digitalWrite(BUZZER_PIN, LOW); 
        digitalWrite(LED_PIN, HIGH); 
        Serial.println("[BLE Task] App Disconnected. Restarting Sleep Timer...");
        bootTime = millis(); 
        pServer->getAdvertising()->start();
    }
};

class AuthCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pChar) {
        String rxValue = pChar->getValue(); 
        if (rxValue.length() > 0) {
            String incomingPin = rxValue; 
            if (stored_pin == "0000") {
                stored_pin = incomingPin;
                prefs.putString("pin", stored_pin);
                isUnlocked = true;
                Serial.println("[BLE Task] SECURITY: Tag Claimed! PIN: " + stored_pin);
            } 
            else if (incomingPin == stored_pin) {
                isUnlocked = true;
                Serial.println("[BLE Task] SECURITY: Auth Success! Tag Unlocked.");
            } 
            else {
                isUnlocked = false;
                Serial.println("[BLE Task] SECURITY: Auth Failed! Incorrect PIN.");
            }
        }
    }
};

class CmdCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pChar) {
        if (!isUnlocked) {
            Serial.println("[BLE Task] REJECTED: Device is LOCKED.");
            return;
        }

        String rxValue = pChar->getValue(); 
        if (rxValue.length() > 0) {
            if (rxValue[0] == '1') {
                digitalWrite(BUZZER_PIN, HIGH);
                digitalWrite(LED_PIN, LOW); 
                Serial.println("[BLE Task] COMMAND: Buzzer & LED ON");
            } 
            else if (rxValue[0] == '0') {
                digitalWrite(BUZZER_PIN, LOW);
                digitalWrite(LED_PIN, HIGH); 
                Serial.println("[BLE Task] COMMAND: Buzzer & LED OFF");
            }
        }
    }
};

// --- TASK 2: POWER MANAGEMENT SUPERVISOR ---
void powerManagementTask(void *pvParameters) {
    for (;;) {
        // If NO phone is connected, and our 10-second window is up...
        if (!deviceConnected && (millis() - bootTime > AWAKE_WINDOW_MS)) {
            Serial.println("[Power Task] No connection. Entering Deep Sleep for 5 seconds...");
            
            pServer->getAdvertising()->stop();
            BLEDevice::deinit(true);
            esp_deep_sleep_start(); // Triggers hardware sleep
        }
        
        // RTOS Yield: Give the CPU back to other tasks for 100ms
        vTaskDelay(100 / portTICK_PERIOD_MS); 
    }
}

// --- TASK 3: HARDWARE MONITOR (FACTORY RESET) ---
void hardwareMonitorTask(void *pvParameters) {
    unsigned long buttonPressTime = 0;
    
    for (;;) {
        if (digitalRead(BOOT_BUTTON) == LOW) {
            if (buttonPressTime == 0) {
                buttonPressTime = millis(); 
            } else if (millis() - buttonPressTime > 5000) {
                Serial.println("[Hardware Task] FACTORY RESET TRIGGERED! Wiping Memory...");
                prefs.clear(); 
                
                digitalWrite(LED_PIN, LOW);
                digitalWrite(BUZZER_PIN, HIGH);
                vTaskDelay(1500 / portTICK_PERIOD_MS); // RTOS friendly delay
                digitalWrite(LED_PIN, HIGH);
                digitalWrite(BUZZER_PIN, LOW);
                
                Serial.println("[Hardware Task] Rebooting...");
                ESP.restart(); 
            }
        } else {
            buttonPressTime = 0; 
        }
        
        // RTOS Yield: Check the button every 50ms
        vTaskDelay(50 / portTICK_PERIOD_MS); 
    }
}

void setup() {
    Serial.begin(115200);
    delay(2000); 
    bootTime = millis(); 
    
    // Hardware Setup
    pinMode(BUZZER_PIN, OUTPUT);
    digitalWrite(BUZZER_PIN, LOW);
    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, HIGH); 
    pinMode(BOOT_BUTTON, INPUT_PULLUP);
    
    // Startup Heartbeat
    digitalWrite(LED_PIN, LOW); delay(50); digitalWrite(LED_PIN, HIGH);

    prefs.begin("proxitrack", false);
    stored_pin = prefs.getString("pin", "0000"); 
    Serial.println("\n--- ProxiNode Waking Up (RTOS Edition) ---");

    // BLE Setup
    BLEDevice::init("ProxiNode");
    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new ServerCallbacks());
    BLEService *pService = pServer->createService(SERVICE_UUID);

    BLECharacteristic *pAuthChar = pService->createCharacteristic(AUTH_CHAR_UUID, BLECharacteristic::PROPERTY_WRITE);
    pAuthChar->setCallbacks(new AuthCallbacks());
    BLECharacteristic *pCmdChar = pService->createCharacteristic(CMD_CHAR_UUID, BLECharacteristic::PROPERTY_WRITE);
    pCmdChar->setCallbacks(new CmdCallbacks());

    pService->start();
    BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06); 
    pAdvertising->start();

    esp_sleep_enable_timer_wakeup(SLEEP_DURATION_US);

    // --- LAUNCH FreeRTOS TASKS ---
    // xTaskCreate(Function, "TaskName", StackSize, Parameters, Priority, TaskHandle)
    xTaskCreate(powerManagementTask, "PowerTask", 2048, NULL, 1, NULL);
    xTaskCreate(hardwareMonitorTask, "HardwareTask", 2048, NULL, 1, NULL);

    Serial.println("[Main] RTOS Scheduler Active. Tasks running.");
}

void loop() {
    // The main loop is dead. Long live FreeRTOS.
    // We delete the Arduino loop task to free up memory for our custom tasks!
    vTaskDelete(NULL); 
}