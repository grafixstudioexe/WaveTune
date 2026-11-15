import 'dart:ui';
import 'package:flutter/material.dart';

void main() {
  runApp(const WaveTuneApp());
}

class WaveTuneApp extends StatelessWidget {
  const WaveTuneApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'WaveTune',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark().copyWith(
        scaffoldBackgroundColor: const Color(0F0F0F12),
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFF00D2FF),
          surface: Color(0xFF18181C),
        ),
      ),
      home: const MainNavigationScreen(),
    );
  }
}

class MainNavigationScreen extends StatefulWidget {
  const MainNavigationScreen({super.key});

  @override
  State<MainNavigationScreen> createState() => _MainNavigationScreenState();
}

class _MainNavigationScreenState extends State<MainNavigationScreen> {
  int _currentIndex = 0;

  final List<Widget> _screens = [
    const HomeScreen(),
    const SearchScreen(),
    const LibraryScreen(),
    const SettingsScreen(),
  ];

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: [
          IndexedStack(
            index: _currentIndex,
            children: _screens,
          ),
          const Positioned(
            left: 0,
            right: 0,
            bottom: 60,
            child: MiniPlayerWidget(),
          ),
        ],
      ),
      bottomNavigationBar: BottomNavigationBar(
        currentIndex: _currentIndex,
        onTap: (index) => setState(() => _currentIndex = index),
        type: BottomNavigationBarType.fixed,
        backgroundColor: const Color(0xFF18181C),
        selectedItemColor: const Color(0xFF00D2FF),
        unselectedItemColor: const Color(0xFF9E9EB0),
        items: const [
          BottomNavigationBarItem(icon: Icon(Icons.home), label: 'Home'),
          BottomNavigationBarItem(icon: Icon(Icons.search), label: 'Search'),
          BottomNavigationBarItem(icon: Icon(Icons.library_music), label: 'Library'),
          BottomNavigationBarItem(icon: Icon(Icons.settings), label: 'Settings'),
        ],
      ),
    );
  }
}

// -----------------------------------------------------------------------------
// Screens
// -----------------------------------------------------------------------------

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('WaveTune', style: TextStyle(fontWeight: FontWeight.bold, color: Color(0xFF00D2FF))),
        backgroundColor: Colors.transparent,
        elevation: 0,
        actions: [
          IconButton(icon: const Icon(Icons.notifications_none), onPressed: () {}),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          const Text('Recently Played', style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
          const SizedBox(height: 12),
          SizedBox(
            height: 140,
            child: ListView.builder(
              scrollDirection: Axis.horizontal,
              itemCount: 5,
              itemBuilder: (context, index) {
                return Container(
                  width: 110,
                  margin: const EdgeInsets.only(right: 12),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Container(
                        height: 90,
                        decoration: BoxDecoration(
                          color: const Color(0xFF2E2E36),
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: const Center(child: Icon(Icons.music_note, color: Color(0xFF00D2FF), size: 40)),
                      ),
                      const SizedBox(height: 6),
                      Text('Track ${index + 1}', maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w600)),
                      const Text('Artist Name', maxLines: 1, style: TextStyle(color: Color(0xFF9E9EB0), fontSize: 12)),
                    ],
                  ),
                );
              },
            ),
          ),
          const SizedBox(height: 24),
          const Text('Made For You', style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
          const SizedBox(height: 12),
          ListView.builder(
            shrinkWrap: true,
            physics: const NeverScrollableScrollPhysics(),
            itemCount: 6,
            itemBuilder: (context, index) {
              return ListTile(
                leading: Container(
                  width: 48,
                  height: 48,
                  decoration: BoxDecoration(
                    color: const Color(0xFF2E2E36),
                    borderRadius: BorderRadius.circular(6),
                  ),
                  child: const Icon(Icons.play_arrow, color: Color(0xFF00D2FF)),
                ),
                title: Text('Featured Song ${index + 1}'),
                subtitle: const Text('Popular Artist'),
                trailing: const Icon(Icons.more_vert, color: Color(0xFF9E9EB0)),
              );
            },
          ),
        ],
      ),
    );
  }
}

class SearchScreen extends StatelessWidget {
  const SearchScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        elevation: 0,
        title: TextField(
          decoration: InputDecoration(
            hintText: 'Search songs, artists, albums...',
            prefixIcon: const Icon(Icons.search, color: Color(0xFF00D2FF)),
            filled: true,
            fillColor: const Color(0xFF18181C),
            contentPadding: const EdgeInsets.symmetric(vertical: 0),
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(24),
              borderSide: BorderSide.none,
            ),
          ),
        ),
      ),
      body: const Center(child: Text('Search Results & Categories', style: TextStyle(color: Color(0xFF9E9EB0)))),
    );
  }
}

class LibraryScreen extends StatelessWidget {
  const LibraryScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Your Library'),
        backgroundColor: Colors.transparent,
        elevation: 0,
      ),
      body: ListView(
        children: const [
          ListTile(leading: Icon(Icons.favorite, color: Color(0xFF00D2FF)), title: Text('Liked Songs'), subtitle: Text('120 tracks')),
          ListTile(leading: Icon(Icons.playlist_play, color: Color(0xFF00D2FF)), title: Text('My Workout Mix'), subtitle: Text('Playlist • ARYNIS')),
          ListTile(leading: Icon(Icons.download_done, color: Color(0xFF00D2FF)), title: Text('Downloaded Tracks'), subtitle: Text('Offline music')),
        ],
      ),
    );
  }
}

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Settings'),
        backgroundColor: Colors.transparent,
        elevation: 0,
      ),
      body: ListView(
        children: const [
          ListTile(
            title: Text('Developer'),
            subtitle: Text('ARYNIS'),
          ),
          ListTile(
            title: Text('Development Studio'),
            subtitle: Text('ARYNIS development'),
          ),
          ListTile(
            title: Text('App Version'),
            subtitle: Text('1.0.0 (WaveTune)'),
          ),
        ],
      ),
    );
  }
}

// -----------------------------------------------------------------------------
// Mini Player Widget
// -----------------------------------------------------------------------------

class MiniPlayerWidget extends StatelessWidget {
  const MiniPlayerWidget({super.key});

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 60,
      margin: const EdgeInsets.symmetric(horizontal: 8),
      padding: const EdgeInsets.symmetric(horizontal: 12),
      decoration: BoxDecoration(
        color: const Color(0xFF18181C),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: const Color(0xFF2E2E36)),
      ),
      child: Row(
        children: [
          Container(
            width: 40,
            height: 40,
            decoration: BoxDecoration(
              color: const Color(0xFF00D2FF),
              borderRadius: BorderRadius.circular(6),
            ),
            child: const Icon(Icons.music_note, color: Colors.black),
          ),
          const SizedBox(width: 12),
          const Expanded(
            child: Column(
              mainAxisAlignment: Center,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Now Playing Song', style: TextStyle(fontWeight: FontWeight.bold, fontSize: 14)),
                Text('ARYNIS Artist', style: TextStyle(color: Color(0xFF9E9EB0), fontSize: 12)),
              ],
            ),
          ),
          IconButton(
            icon: const Icon(Icons.play_arrow, color: Color(0xFF00D2FF)),
            onPressed: () {},
          ),
          IconButton(
            icon: const Icon(Icons.skip_next, color: Colors.white),
            onPressed: () {},
          ),
        ],
      ),
    );
  }
}
