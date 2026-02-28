/* * Project: ProxiTrack - Official Hardware Handover (DIPEX 2026)
 * Firmware: V8.0 (Master Release: Max Power + Security + Sleep + LED)
 * Hardware: ESP32-C3 Super Mini
 */

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include <Preferences.h>
#include <esp_sleep.h> 

// --- PIN DEFINITIONS ---
#define BUZZER_PIN 10
#define LED_PIN 8 // ESP32-C3 Super Mini Onboard Blue LED (Active Low)

// --- CONTRACT UUIDS ---
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define AUTH_CHAR_UUID      "8d8218b6-97bc-4527-a8db-130940ddb633"
#define CMD_CHAR_UUID       "beb5483e-36e1-4688-b7f5-ea07361b26a8"

// --- GLOBAL STATE ---
Preferences prefs;
BLEServer* pServer = NULL;
String stored_pin = "0000";
volatile bool isUnlocked = false; 

// --- POWER MANAGEMENT VARIABLES ---
bool deviceConnected = false;
unsigned long bootTime = 0;
const unsigned long AWAKE_WINDOW_MS = 10000; 
const uint64_t SLEEP_DURATION_US = 5000000;  

// --- GATT CALLBACKS ---

class ServerCallbacks: public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
        deviceConnected = true; 
        isUnlocked = false;     
        Serial.println("STATUS: App Connected. Sleep Timer Canceled! Awaiting PIN...");
        pServer->getAdvertising()->start();
    }
    
    void onDisconnect(BLEServer* pServer) {
        deviceConnected = false; 
        isUnlocked = false;
        
        // Failsafe: Turn off buzzer and LED if app crashes/disconnects
        digitalWrite(BUZZER_PIN, LOW); 
        digitalWrite(LED_PIN, HIGH); // HIGH is OFF for Active Low
        
        Serial.println("STATUS: App Disconnected. Preparing for sleep cycle...");
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
                Serial.println("SECURITY: Tag Claimed! Locked to PIN: " + stored_pin);
            } 
            else if (incomingPin == stored_pin) {
                isUnlocked = true;
                Serial.println("SECURITY: Auth Success! Tag Unlocked.");
            } 
            else {
                isUnlocked = false;
                Serial.println("SECURITY: Auth Failed! Incorrect PIN.");
            }
        }
    }
};

class CmdCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pChar) {
        if (!isUnlocked) {
            Serial.println("REJECTED: Device is LOCKED. Authenticate first.");
            return;
        }

        String rxValue = pChar->getValue(); 
        if (rxValue.length() > 0) {
            if (rxValue[0] == '1') {
                digitalWrite(BUZZER_PIN, HIGH);
                digitalWrite(LED_PIN, LOW); // LOW is ON for Active Low LED
                Serial.println("COMMAND: Buzzer & LED ON");
            } 
            else if (rxValue[0] == '0') {
                digitalWrite(BUZZER_PIN, LOW);
                digitalWrite(LED_PIN, HIGH); // HIGH is OFF
                Serial.println("COMMAND: Buzzer & LED OFF");
            }
        }
    }
};

void setup() {
    Serial.begin(115200);
    delay(2000); 
    
    bootTime = millis(); 
    
    // Setup Audio & Visuals
    pinMode(BUZZER_PIN, OUTPUT);
    digitalWrite(BUZZER_PIN, LOW);
    
    pinMode(LED_PIN, OUTPUT);
    digitalWrite(LED_PIN, HIGH); // Turn LED OFF initially
    
    // Quick visual heartbeat to show it's awake
    digitalWrite(LED_PIN, LOW); delay(50); digitalWrite(LED_PIN, HIGH);

    prefs.begin("proxitrack", false);
    stored_pin = prefs.getString("pin", "0000"); 
    Serial.println("\n--- ProxiNode Waking Up ---");
    Serial.println("BOOT: Current Stored PIN is: " + stored_pin);

    BLEDevice::init("ProxiNode");
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

    BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID);
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06); 
    pAdvertising->start();

    esp_sleep_enable_timer_wakeup(SLEEP_DURATION_US);

    Serial.println("SYSTEM READY: Advertising for 10 seconds at Max Power...");
}

void loop() {
    if (!deviceConnected && (millis() - bootTime > AWAKE_WINDOW_MS)) {
        Serial.println("POWER: No connection. Entering Deep Sleep for 5 seconds...");
        
        pServer->getAdvertising()->stop();
        BLEDevice::deinit(true);
        esp_deep_sleep_start();
    }
    delay(10); 
}