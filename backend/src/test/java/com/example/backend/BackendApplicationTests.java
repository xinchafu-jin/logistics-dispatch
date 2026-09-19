package com.example.backend;

import com.example.backend.config.SecurityConfig;
import com.google.ortools.Loader;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.LinearExpr;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import com.google.ortools.Loader;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import org.springframework.security.crypto.encrypt.TextEncryptor;

import static org.junit.jupiter.api.Assertions.*;

//@SpringBootTest
class BackendApplicationTests {
    private static final String PASSWORD = "test-password-test-password-test-password";
    private static final String SALT = "0123456789abcdef";
    private static final String API_KEY = "sk-test1•••••••••••••••";

    private final SecurityConfig securityConfig = new SecurityConfig();

    @Test
    void 同一把Key加密兩次_密文不同() {
        TextEncryptor encryptor = securityConfig.aiApiKeyEncryptor(PASSWORD, SALT);

        String first = encryptor.encrypt(API_KEY);
        String second = encryptor.encrypt(API_KEY);

        assertNotEquals(first, second);
        assertFalse(first.contains(API_KEY));
    }

    @Test
    void 兩份密文都能解回原本的Key() {
        TextEncryptor encryptor = securityConfig.aiApiKeyEncryptor(PASSWORD, SALT);

        String first = encryptor.encrypt(API_KEY);
        String second = encryptor.encrypt(API_KEY);

        assertEquals(API_KEY, encryptor.decrypt(first));
        assertEquals(API_KEY, encryptor.decrypt(second));
    }

    @Test
    void 換了password就解不開() {
        TextEncryptor original = securityConfig.aiApiKeyEncryptor(PASSWORD, SALT);
        TextEncryptor other = securityConfig.aiApiKeyEncryptor("another-password-another-password-xx", SALT);

        String encrypted = original.encrypt(API_KEY);

        assertThrows(IllegalStateException.class, () -> other.decrypt(encrypted));
    }

    @Test
    void password太短_建立時就擋下() {
        assertThrows(IllegalStateException.class,
                () -> securityConfig.aiApiKeyEncryptor("too-short", SALT));
    }

    @Test
    void salt不是16進位_建立時就擋下() {
        assertThrows(IllegalStateException.class,
                () -> securityConfig.aiApiKeyEncryptor(PASSWORD, "xyz"));
    }
    @Test
    void contextLoads() {
        Loader.loadNativeLibraries();
        System.out.println("成功");

        CpModel cpModel = new CpModel();
        IntVar x = cpModel.newIntVar(0, 10, "x");
        IntVar y = cpModel.newIntVar(0, 10, "y");


        // 4. 加入限制條件: x + y <= 10
        cpModel.addLessThan(LinearExpr.sum(new IntVar[] {x, y}), 11);
        // 5. 設定目標函數: 最大化 2*x + 3*y
//  新版正確語法
        cpModel.maximize(LinearExpr.weightedSum(new IntVar[] {x, y}, new long[] {2, 3}));

        // 6. 執行求解
        CpSolver solver = new CpSolver();
        CpSolverStatus status = solver.solve(cpModel);

        // 7. 輸出結果
        if (status == CpSolverStatus.OPTIMAL || status == CpSolverStatus.FEASIBLE) {
            System.out.println("x = " + solver.value(x));
            System.out.println("y = " + solver.value(y));
        }
    }




    }
